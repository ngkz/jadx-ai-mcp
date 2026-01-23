package com.zin.jadxaimcp.server.routes;

import io.javalin.http.Context;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.JavaNode;
import jadx.api.metadata.ICodeNodeRef;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import jadx.api.data.ICodeComment;
import jadx.api.data.CommentStyle;
import jadx.api.data.impl.JadxCodeComment;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxNodeRef;
import jadx.gui.JadxWrapper;
import jadx.gui.ui.MainWindow;
import jadx.gui.ui.panel.ContentPanel;
import jadx.gui.ui.codearea.AbstractCodeContentPanel;
import jadx.gui.ui.codearea.AbstractCodeArea;
import jadx.gui.ui.codearea.CodeArea;
import jadx.gui.settings.JadxProject;
import jadx.gui.utils.UiUtils;

import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.instructions.args.SSAVar;
import jadx.api.metadata.annotations.VarNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zin.jadxaimcp.utils.JadxAIMCPPluginError;
import com.zin.jadxaimcp.utils.MethodSignatureUtils;
import com.zin.jadxaimcp.utils.MethodSignatureUtils.MethodSignature;

public class RefactoringRoutes {
    private static final Logger logger = LoggerFactory.getLogger(RefactoringRoutes.class);
    private final MainWindow mainWindow;
    
    public RefactoringRoutes(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    /**
     * @return void
     * @param Context
     * 
     * This routing method handle the /rename-class mcp tool call's http request, After validating the 
     * required http params, it tries to find the class which has to be renamed. If it is found
     * then it renames it using NodeRenamedByUser class' events methods 'setRenameNode' and 'setResetName'.
     * Then it sends these events using MainWindows's send() method.
     */
    public void handleRenameClass(Context ctx) {
        String className = ctx.queryParam("class_name");
        String newName = ctx.queryParam("new_name");

        if (validateParams(ctx, className, newName)) return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    ICodeNodeRef nodeRef = cls.getCodeNodeRef();
                    NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, cls.getName(), newName);
                    event.setRenameNode(cls.getClassNode());
                    event.setResetName(newName.isEmpty());
                    mainWindow.events().send(event);

                    logger.info("Renaming Class {} to {}", cls.getName(), newName);
                    ctx.json(Map.of("result", "Renamed Class " + cls.getName() + " to " + newName));
                    return;
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Class " + className + " not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the class: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @param
     * @return
     * 
     * This routing method handle the /rename-method mcp tool call's http request, After validating the 
     * required http params, it tries to find the class whose method has to be renamed. If it is found
     * then it renames it using NodeRenamedByUser class' events methods 'setRenameNode' and 'setResetName'.
     * Then it sends these events using MainWindows's send() method.
     * 
     * Supports full method signature format: methodName(paramType1, paramType2):returnType
     * Also supports matching by both renamed class name and original class name.
     * 
     * Parameters:
     * - class_name: The class containing the method (supports both renamed and original names)
     * - method_name: Method signature, e.g., "methodName(int, String):void" or just "methodName"
     * - new_name: The new name for the method
     */
    public void handleRenameMethod(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodNameInput = ctx.queryParam("method_name");
        String newName = ctx.queryParam("new_name");

        if (validateParams(ctx, className, methodNameInput, newName)) return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            
            // Extract method name and signature from input
            // Support formats: "methodName", "methodName(params):ret", "ClassName.methodName", "ClassName.methodName(params):ret"
            String methodPart = methodNameInput;
            
            // If method_name contains a class prefix (e.g., "ShakeUtil.methodName"), extract just the method part
            int parenIndex = methodNameInput.indexOf('(');
            if (parenIndex == -1) {
                // No signature, check for class prefix
                int lastDot = methodNameInput.lastIndexOf('.');
                if (lastDot != -1) {
                    methodPart = methodNameInput.substring(lastDot + 1);
                    logger.info("Extracted method name '{}' from input '{}'", methodPart, methodNameInput);
                }
            } else {
                // Has signature, check for class prefix before the method name
                int lastDotBeforeParen = methodNameInput.lastIndexOf('.', parenIndex);
                if (lastDotBeforeParen != -1) {
                    methodPart = methodNameInput.substring(lastDotBeforeParen + 1);
                    logger.info("Extracted method signature '{}' from input '{}'", methodPart, methodNameInput);
                }
            }
            
            // Parse method signature (now without class prefix)
            MethodSignature methodSig = MethodSignatureUtils.parseMethodSignatureOnly(methodPart);
            
            if (methodSig == null) {
                JadxAIMCPPluginError.handleError(ctx, 400, "Invalid method signature format. Expected: methodName(paramTypes):returnType or methodName", logger);
                return;
            }
            
            // If no signature provided, log a warning
            if (!methodSig.hasSignature) {
                logger.warn("No method signature provided for '{}'. This may match the wrong overloaded method. " +
                           "Recommended format: methodName(paramType1, paramType2):returnType", methodNameInput);
            }
            
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                // Match both renamed name (FullName) and original name (RawName)
                String clsFullName = cls.getFullName();
                String clsRawName = cls.getRawName();
                
                boolean classMatches = clsFullName.equals(className) || clsRawName.equals(className);
                
                if (classMatches) {
                    for (JavaMethod method : cls.getMethods()) {
                        if (!method.getName().equals(methodSig.methodName)) {
                            continue;
                        }
                        
                        // If no signature provided, match by name only (first match)
                        if (!methodSig.hasSignature) {
                            doRenameMethod(ctx, method, newName);
                            return;
                        }
                        
                        // Match by full signature
                        if (MethodSignatureUtils.matchMethodSignature(method, methodSig)) {
                            doRenameMethod(ctx, method, newName);
                            return;
                        }
                    }
                }
            }
            
            // Build helpful error message with available methods
            StringBuilder similarMethods = new StringBuilder();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                String clsFullName = cls.getFullName();
                String clsRawName = cls.getRawName();
                if (clsFullName.equals(className) || clsRawName.equals(className)) {
                    for (JavaMethod method : cls.getMethods()) {
                        if (method.getName().equals(methodSig.methodName)) {
                            similarMethods.append(MethodSignatureUtils.buildMethodSignature(cls, method)).append("; ");
                        }
                    }
                }
            }
            
            String errorMsg = "Method not found: " + methodNameInput + " in class " + className;
            if (similarMethods.length() > 0) {
                errorMsg += ". Available methods with same name: " + similarMethods.toString();
            }
            JadxAIMCPPluginError.handleError(ctx, 404, errorMsg, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the method: " + e.getMessage(), e, logger);
        }
    }
    
    /**
     * Helper method to perform the actual method rename
     */
    private void doRenameMethod(Context ctx, JavaMethod method, String newName) {
        ICodeNodeRef nodeRef = method.getCodeNodeRef();
        NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, method.getName(), newName);
        event.setRenameNode(method.getMethodNode());
        event.setResetName(newName.isEmpty());
        mainWindow.events().send(event);

        logger.info("Renaming method {} to {}", method.getName(), newName);
        ctx.json(Map.of("result", "Renamed method " + method.getName() + " to " + newName));
    }

    /**
     * 
     * @param ctx
     * @return
     * 
     * This routing method handle the /rename-field mcp tool call's http request, After validating the 
     * required http params, it tries to find the class whose method has to be renamed. If it is found
     * then it renames it using NodeRenamedByUser class' events methods 'setRenameNode' and 'setResetName'.
     * Then it sends these events using MainWindows's send() method.
     */
    public void handleRenameField(Context ctx) {
        String className = ctx.queryParam("class_name");
        String oldFieldName = ctx.queryParam("field_name");
        String newFieldName = ctx.queryParam("new_field_name");

        if (validateParams(ctx, className, oldFieldName, newFieldName)) return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaField field : cls.getFields()) {
                        if (field.getName().equals(oldFieldName)) {
                            ICodeNodeRef nodeRef = field.getCodeNodeRef();
                            NodeRenamedByUser event = new NodeRenamedByUser(nodeRef, field.getName(), newFieldName);
                            event.setRenameNode(field.getFieldNode());
                            event.setResetName(newFieldName.isEmpty());
                            mainWindow.events().send(event);

                            logger.info("Renaming field {} to {}", field.getName(), newFieldName);
                            ctx.json(Map.of("result", "Renamed field " + field.getName() + " to " + newFieldName));
                            return;
                        }
                    }
                }
            }
            JadxAIMCPPluginError.handleError(ctx, 404, "Either Class " + className + " not found or the Field not found.", logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the field: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This routing method handle the /rename-variable mcp tool call's http request.
     * It validates required params: class_name, method_name, variable_name, new_name.
     * It tries to find the method and then iterates over its SSA variables to find the matching variable.
     * If found, it renames it using NodeRenamedByUser event.
     */
    public void handleRenameVariable(Context ctx) {
        String className = ctx.queryParam("class_name");
        String methodName = ctx.queryParam("method_name");
        String variableName = ctx.queryParam("variable_name");
        String newName = ctx.queryParam("new_name");
        
        // Optional params for more specific targeting
        String regStr = ctx.queryParam("reg");
        String ssaStr = ctx.queryParam("ssa");

        if (validateParams(ctx, className, methodName, variableName, newName)) return;

        // Strip method signature if present
        if (methodName.contains("(")) {
            methodName = methodName.substring(0, methodName.indexOf('('));
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    for (JavaMethod method : cls.getMethods()) {
                        String fullMethodName = cls.getFullName() + "." + method.getName();
                        if (method.getName().equals(methodName) || fullMethodName.equalsIgnoreCase(methodName)) {
                            MethodNode methodNode = method.getMethodNode();
                            if (methodNode == null) continue;

                            List<SSAVar> sVars = methodNode.getSVars();

                            // Ensure class is processed to populate SSA variables
                            if (sVars.isEmpty()) {
                                logger.info("SSA variables empty for method {}, forcing class reload and processing...", method.getName());
                                try {
                                    // defined class need to be unloaded to reset state and allow full processing
                                    cls.getClassNode().unload();
                                    cls.getClassNode().root().getProcessClasses().forceProcess(cls.getClassNode());

                                    // Re-fetch method node and sVars after processing because unload/load recreates MethodNode objects
                                    MethodNode newMethodNode = cls.getClassNode().searchMethodByShortName(method.getName());
                                    if (newMethodNode != null) {
                                         methodNode = newMethodNode;
                                         sVars = methodNode.getSVars();
                                         logger.info("Class reloaded. New SSA variables count: {}", sVars != null ? sVars.size() : "null");
                                    } else {
                                         logger.error("Failed to find method {} after reload", method.getName());
                                    }

                                } catch (Exception e) {
                                    logger.error("Failed to force process class {}", cls.getName(), e);
                                }
                            }

                            if (sVars == null || sVars.isEmpty()) continue;

                            for (SSAVar sVar : sVars) {
                                boolean nameMatch = variableName.equals(sVar.getName());
                                boolean regMatch = regStr == null || regStr.isEmpty() || String.valueOf(sVar.getRegNum()).equals(regStr);
                                boolean ssaMatch = ssaStr == null || ssaStr.isEmpty() || String.valueOf(sVar.getVersion()).equals(ssaStr);
                                if (nameMatch && regMatch && ssaMatch) {
                                    VarNode varNode = VarNode.get(methodNode, sVar);
                                    if (varNode != null) {
                                        NodeRenamedByUser event = new NodeRenamedByUser(varNode, variableName, newName);
                                        event.setRenameNode(varNode);
                                        event.setResetName(newName.isEmpty());
                                        mainWindow.events().send(event);
                                        
                                        logger.info("Renamed variable {} to {} in method {}", variableName, newName, method.getName());
                                        ctx.json(Map.of("result", "Rename variable " + variableName + " to " + newName));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            JadxAIMCPPluginError.handleError(ctx, 404, "Variable " + variableName + " not found in method " + methodName, logger);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to rename the variable: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This routing method handle the /rename-package mcp tool call's http request, After validating the 
     * required http params, It iterates over list of class one by one under the oldpackage and 
     * then it renames it using NodeRenamedByUser class' events methods 'setRenameNode' and 'setResetName'.
     * Then it sends these events using MainWindows's send() method.
     */
    public void handleRenamePackage(Context ctx) {
        String oldPackage = ctx.queryParam("old_package_name");
        String newPackage = ctx.queryParam("new_package_name");

        if (validateParams(ctx, oldPackage, newPackage)) return;

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            List<String> errors = new ArrayList<>();
            int count = 0;
            int total = 0;

            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                String fullName = cls.getFullName();
                if (fullName.startsWith(oldPackage + ".") || fullName.equals(oldPackage)) {
                    total++;
                    try {
                        String relativePath = fullName.substring(oldPackage.length());
                        String newFullName = newPackage + relativePath;

                        NodeRenamedByUser event = new NodeRenamedByUser(cls.getCodeNodeRef(), cls.getName(), newFullName);
                        event.setRenameNode(cls.getClassNode());
                        event.setResetName(false);
                        mainWindow.events().send(event);
                        count++;
                    } catch (Exception e) {
                        errors.add("Failed to rename " + fullName + ": " + e.getMessage());
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("renamed", count);
            result.put("total", total);
            result.put("errors", errors);
            ctx.json(result);
        } catch (Exception e) {
            JadxAIMCPPluginError.handleError(ctx, "Internal error occurred while trying to rename the package: " + e.getMessage(), e, logger);
        }
    }

    /**
     * @return void
     * @param Context
     * 
     * This routing method handles the /add-comment mcp tool call's http request. It adds a comment
     * to a specified class, method, or field in the decompiled code.
     * 
     * Parameters:
     * - target_type: "class", "method", or "field"
     * - class_name: Fully qualified class name
     * - method_name: Method name (for method comments)
     * - field_name: Field name (for field comments)
     * - comment: The comment text to add
     * - style: Comment style (optional, default: "LINE")
     *          Options: "LINE", "BLOCK", "BLOCK_CONDENSED", "JAVADOC", "JAVADOC_CONDENSED"
     */
    public void handleAddComment(Context ctx) {
        String targetType = ctx.queryParam("target_type");
        String className = ctx.queryParam("class_name");
        String commentText = ctx.queryParam("comment");
        String styleParam = ctx.queryParam("style");

        if (validateParams(ctx, targetType, className, commentText)) return;
        
        // Parse comment style (default to LINE)
        CommentStyle commentStyle = CommentStyle.LINE;
        if (styleParam != null && !styleParam.isEmpty()) {
            try {
                commentStyle = CommentStyle.valueOf(styleParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid comment style '{}', using LINE as default. Valid options: LINE, BLOCK, BLOCK_CONDENSED, JAVADOC, JAVADOC_CONDENSED", styleParam);
            }
        }

        try {
            JadxWrapper wrapper = mainWindow.getWrapper();
            if (wrapper == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "JADX wrapper is not initialized. Please open an APK file first.", logger);
                return;
            }
            
            JadxProject project = mainWindow.getProject();
            if (project == null) {
                JadxAIMCPPluginError.handleError(ctx, 500, "JADX project is not initialized. Please open an APK file first.", logger);
                return;
            }
            
            JavaNode targetNode = null;
            
            for (JavaClass cls : wrapper.getIncludedClassesWithInners()) {
                // Match both renamed name (FullName) and original name (RawName)
                boolean classMatches = cls.getFullName().equals(className) || cls.getRawName().equals(className);
                
                if (classMatches) {
                    if ("class".equalsIgnoreCase(targetType)) {
                        targetNode = cls;
                        break;
                    } else if ("method".equalsIgnoreCase(targetType)) {
                        String methodNameInput = ctx.queryParam("method_name");
                        if (methodNameInput == null || methodNameInput.isEmpty()) {
                            JadxAIMCPPluginError.handleError(ctx, 400, "method_name is required for method comments", logger);
                            return;
                        }
                        
                        // Parse method signature if present
                        MethodSignature methodSig = MethodSignatureUtils.parseMethodSignature(cls.getFullName() + "." + methodNameInput);
                        if (methodSig == null) {
                            // Try parsing as just method name or full path
                            methodSig = new MethodSignature();
                            methodSig.methodName = methodNameInput.contains("(") ? 
                                methodNameInput.substring(0, methodNameInput.indexOf('(')) : methodNameInput;
                            methodSig.hasSignature = methodNameInput.contains("(");
                            if (methodSig.hasSignature) {
                                // Re-parse with full class path
                                methodSig = MethodSignatureUtils.parseMethodSignature(cls.getFullName() + "." + methodNameInput);
                            }
                        }
                        
                        String searchMethodName = methodSig != null ? methodSig.methodName : methodNameInput;
                        
                        for (JavaMethod method : cls.getMethods()) {
                            if (!method.getName().equals(searchMethodName)) {
                                continue;
                            }
                            
                            // If no signature provided, match by name only (first match)
                            if (methodSig == null || !methodSig.hasSignature) {
                                targetNode = method;
                                break;
                            }
                            
                            // Match by full signature
                            if (MethodSignatureUtils.matchMethodSignature(method, methodSig)) {
                                targetNode = method;
                                break;
                            }
                        }
                        
                        if (targetNode == null) {
                            // Build helpful error message with available methods
                            StringBuilder availableMethods = new StringBuilder();
                            for (JavaMethod method : cls.getMethods()) {
                                if (method.getName().equals(searchMethodName)) {
                                    availableMethods.append(MethodSignatureUtils.buildMethodSignature(cls, method)).append("; ");
                                }
                            }
                            String errorMsg = "Method " + methodNameInput + " not found in class " + className;
                            if (availableMethods.length() > 0) {
                                errorMsg += ". Available methods: " + availableMethods.toString();
                            }
                            JadxAIMCPPluginError.handleError(ctx, 404, errorMsg, logger);
                            return;
                        }
                        break;
                        
                    } else if ("field".equalsIgnoreCase(targetType)) {
                        String fieldName = ctx.queryParam("field_name");
                        if (fieldName == null || fieldName.isEmpty()) {
                            JadxAIMCPPluginError.handleError(ctx, 400, "field_name is required for field comments", logger);
                            return;
                        }
                        
                        for (JavaField field : cls.getFields()) {
                            if (field.getName().equals(fieldName)) {
                                targetNode = field;
                                break;
                            }
                        }
                        
                        if (targetNode == null) {
                            JadxAIMCPPluginError.handleError(ctx, 404, "Field " + fieldName + " not found in class " + className, logger);
                            return;
                        }
                        break;
                    }
                }
            }
            
            if (targetNode == null) {
                JadxAIMCPPluginError.handleError(ctx, 404, "Target not found: " + className, logger);
                return;
            }
            
            logger.debug("Found target node: {} (type: {})", targetNode.getName(), targetNode.getClass().getSimpleName());
            
            // Create and save comment using JADX's code data API
            JadxCodeData codeData = project.getCodeData();
            logger.debug("Current codeData: {}", codeData);
            
            if (codeData == null) {
                logger.info("Creating new JadxCodeData");
                codeData = new JadxCodeData();
            }
            
            List<ICodeComment> comments = new ArrayList<>(codeData.getComments());
            logger.debug("Existing comments count: {}", comments.size());
            
            JadxNodeRef nodeRef = JadxNodeRef.forJavaNode(targetNode);
            logger.debug("Created node ref: {}", nodeRef);
            
            // Remove existing comment for the same node (if any) to avoid duplicates
            comments.removeIf(c -> {
                boolean sameNode = c.getNodeRef() != null && c.getNodeRef().equals(nodeRef);
                boolean sameCodeRef = (c.getCodeRef() == null) == true; // Both should be null for node-level comments
                return sameNode && sameCodeRef;
            });
            logger.debug("After removing duplicates, comments count: {}", comments.size());
            
            ICodeComment newComment = new JadxCodeComment(nodeRef, commentText, commentStyle);
            logger.debug("Created comment with style: {}", commentStyle);
            
            comments.add(newComment);
            Collections.sort(comments);
            codeData.setComments(comments);
            project.setCodeData(codeData);
            
            logger.info("Saving code data to project...");
            try {
                project.save();
                logger.info("Project saved successfully");
            } catch (Exception saveEx) {
                logger.warn("Failed to save project: {}", saveEx.getMessage());
            }
            
            logger.info("Reloading code data and refreshing UI...");
            
            // Reload code data (same as JADX UI does)
            try {
                wrapper.reloadCodeData();
                logger.info("Code data reloaded");
            } catch (Exception reloadEx) {
                logger.warn("Failed to reload code data: {}", reloadEx.getMessage());
            }
            
            // Refresh current code view on EDT (same as JADX CommentDialog does)
            // Use CodeArea.refreshClass() which preserves scroll position via CaretPositionFix
            UiUtils.uiRun(() -> {
                try {
                    ContentPanel contentPanel = mainWindow.getTabbedPane().getSelectedContentPanel();
                    if (contentPanel instanceof AbstractCodeContentPanel) {
                        AbstractCodeArea codeArea = ((AbstractCodeContentPanel) contentPanel).getCodeArea();
                        if (codeArea instanceof CodeArea) {
                            ((CodeArea) codeArea).refreshClass();
                            logger.info("Code area refreshed with refreshClass()");
                        } else {
                            codeArea.refresh();
                            logger.info("Code area refreshed with refresh()");
                        }
                    }
                } catch (Exception refreshEx) {
                    logger.error("Failed to refresh code area after comment", refreshEx);
                }
            });
            
            logger.info("Successfully added {} style comment to {} {}", commentStyle, targetType, targetNode.getName());
            ctx.json(Map.of(
                "result", "Added " + commentStyle + " style comment to " + targetType + " " + targetNode.getName(),
                "style", commentStyle.toString()
            ));
            
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isEmpty()) {
                errorMsg = e.getClass().getName();
            }
            logger.error("Error adding comment", e);
            JadxAIMCPPluginError.handleError(ctx, "Internal error while trying to add comment: " + errorMsg, e, logger);
        }
    }

    // Helper methods

    /**
     * @param Context, String, String
     * @return boolean
     * 
     * This method is used to validate the availability of required http params in RefactoringRoutes
     * MCP tool's HTTP requests. If params are ok return true else return false.
     */
    private boolean validateParams(Context ctx, String p1, String p2) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty()) {
            //ctx.status(400).json(Map.of("error", "Missing required parameters."));
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

    /**
     * @param Context, String, String, String
     * @return boolean
     * 
     * This method is used to validate the availability of required http params in RefactoringRoutes
     * MCP tool's HTTP requests. If params are ok return true else return false.
     */
    private boolean validateParams(Context ctx, String p1, String p2, String p3) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty()) {
            //ctx.status(400).json(Map.of("error", "Missing required parameters."));
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

    /**
     * @param Context, String, String, String, String
     * @return boolean
     * 
     * This method is used to validate the availability of required http params in RefactoringRoutes
     * MCP tool's HTTP requests. If params are ok return true else return false.
     */
    private boolean validateParams(Context ctx, String p1, String p2, String p3, String p4) {
        if (p1 == null || p1.isEmpty() || p2 == null || p2.isEmpty() || p3 == null || p3.isEmpty() || p4 == null || p4.isEmpty()) {
            JadxAIMCPPluginError.handleError(ctx, 400, "Missing required parameters", logger);
            return true;
        }
        return false;
    }

}
