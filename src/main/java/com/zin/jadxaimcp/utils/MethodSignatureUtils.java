package com.zin.jadxaimcp.utils;

import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.core.dex.instructions.args.ArgType;

import java.util.List;

/**
 * Utility class for parsing, matching and building method signatures.
 */
public class MethodSignatureUtils {
    
    /**
     * Data class to hold parsed method signature information.
     */
    public static class MethodSignature {
        public String className;
        public String methodName;
        public String[] paramTypes;
        public String returnType;
        public boolean hasSignature;
    }
    
    /**
     * Parse method signature only (without class name prefix).
     * Format: methodName(paramType1, paramType2):returnType
     * Or: methodName (without signature)
     */
    public static MethodSignature parseMethodSignatureOnly(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        MethodSignature sig = new MethodSignature();
        
        // Check if signature is present
        int parenIndex = input.indexOf('(');
        if (parenIndex == -1) {
            // No signature, just method name
            sig.methodName = input.trim();
            sig.hasSignature = false;
            return sig;
        }
        
        // Has signature
        sig.hasSignature = true;
        sig.methodName = input.substring(0, parenIndex).trim();
        
        // Extract parameters
        int closeParenIndex = input.indexOf(')');
        if (closeParenIndex == -1) {
            return null;
        }
        String paramsStr = input.substring(parenIndex + 1, closeParenIndex).trim();
        if (paramsStr.isEmpty()) {
            sig.paramTypes = new String[0];
        } else {
            sig.paramTypes = paramsStr.split(",");
            for (int i = 0; i < sig.paramTypes.length; i++) {
                sig.paramTypes[i] = sig.paramTypes[i].trim();
            }
        }
        
        // Extract return type if present
        int colonIndex = input.indexOf(':', closeParenIndex);
        if (colonIndex != -1) {
            sig.returnType = input.substring(colonIndex + 1).trim();
        }
        
        return sig;
    }
    
    /**
     * Parse method signature from input string with class name.
     * Format: ClassName.methodName(paramType1, paramType2):returnType
     * Or: ClassName.methodName (without signature)
     */
    public static MethodSignature parseMethodSignature(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        
        MethodSignature sig = new MethodSignature();
        
        // Check if signature is present
        int parenIndex = input.indexOf('(');
        if (parenIndex == -1) {
            // No signature, just class.method
            int lastDot = input.lastIndexOf('.');
            if (lastDot == -1) {
                return null;
            }
            sig.className = input.substring(0, lastDot);
            sig.methodName = input.substring(lastDot + 1);
            sig.hasSignature = false;
            return sig;
        }
        
        // Has signature
        sig.hasSignature = true;
        
        // Extract class.method part
        String classAndMethod = input.substring(0, parenIndex);
        int lastDot = classAndMethod.lastIndexOf('.');
        if (lastDot == -1) {
            return null;
        }
        sig.className = classAndMethod.substring(0, lastDot);
        sig.methodName = classAndMethod.substring(lastDot + 1);
        
        // Extract parameters
        int closeParenIndex = input.indexOf(')');
        if (closeParenIndex == -1) {
            return null;
        }
        String paramsStr = input.substring(parenIndex + 1, closeParenIndex).trim();
        if (paramsStr.isEmpty()) {
            sig.paramTypes = new String[0];
        } else {
            // Split by comma, handle spaces
            String[] params = paramsStr.split("\\s*,\\s*");
            sig.paramTypes = params;
        }
        
        // Extract return type (optional)
        if (closeParenIndex + 1 < input.length() && input.charAt(closeParenIndex + 1) == ':') {
            sig.returnType = input.substring(closeParenIndex + 2).trim();
        }
        
        return sig;
    }
    
    /**
     * Match a JavaMethod against the parsed signature.
     */
    public static boolean matchMethodSignature(JavaMethod method, MethodSignature sig) {
        // Get method's argument types
        List<ArgType> argTypes = method.getMethodNode().getMethodInfo().getArgumentsTypes();
        
        if (argTypes.size() != sig.paramTypes.length) {
            return false;
        }
        
        for (int i = 0; i < argTypes.size(); i++) {
            String expectedType = sig.paramTypes[i].trim();
            String actualType = argTypes.get(i).toString();
            
            // Normalize types for comparison
            if (!normalizeType(expectedType).equals(normalizeType(actualType))) {
                return false;
            }
        }
        
        // Optionally match return type if provided
        if (sig.returnType != null && !sig.returnType.isEmpty()) {
            String actualReturnType = method.getMethodNode().getMethodInfo().getReturnType().toString();
            if (!normalizeType(sig.returnType).equals(normalizeType(actualReturnType))) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Normalize type string for comparison.
     * Handles: int vs I, java.lang.String vs String, etc.
     */
    public static String normalizeType(String type) {
        if (type == null) return "";
        type = type.trim();
        
        // Remove generic parameters (e.g., List<String> -> List)
        int genericIndex = type.indexOf('<');
        if (genericIndex != -1) {
            type = type.substring(0, genericIndex);
        }
        
        // Handle array notation
        boolean isArray = type.endsWith("[]");
        if (isArray) {
            type = type.substring(0, type.length() - 2);
        }
        
        // Normalize primitive types and common classes
        switch (type) {
            case "void": case "V": type = "void"; break;
            case "boolean": case "Z": type = "boolean"; break;
            case "byte": case "B": type = "byte"; break;
            case "char": case "C": type = "char"; break;
            case "short": case "S": type = "short"; break;
            case "int": case "I": type = "int"; break;
            case "long": case "J": type = "long"; break;
            case "float": case "F": type = "float"; break;
            case "double": case "D": type = "double"; break;
            default:
                // Remove java.lang. prefix for common types
                if (type.startsWith("java.lang.")) {
                    type = type.substring(10);
                }
                // Handle L...;  format (JVM format)
                if (type.startsWith("L") && type.endsWith(";")) {
                    type = type.substring(1, type.length() - 1).replace('/', '.');
                    if (type.startsWith("java.lang.")) {
                        type = type.substring(10);
                    }
                }
                break;
        }
        
        return isArray ? type + "[]" : type;
    }
    
    /**
     * Build a human-readable method signature string.
     */
    public static String buildMethodSignature(JavaClass cls, JavaMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getFullName()).append(".").append(method.getName()).append("(");
        
        List<ArgType> argTypes = method.getMethodNode().getMethodInfo().getArgumentsTypes();
        for (int i = 0; i < argTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(normalizeType(argTypes.get(i).toString()));
        }
        
        sb.append("):").append(normalizeType(method.getMethodNode().getMethodInfo().getReturnType().toString()));
        return sb.toString();
    }
}
