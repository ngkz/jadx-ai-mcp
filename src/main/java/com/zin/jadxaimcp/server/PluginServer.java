package com.zin.jadxaimcp.server;

import io.javalin.Javalin;
import jadx.gui.ui.MainWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxaimcp.utils.JadxAIMCPBanner;
import com.zin.jadxaimcp.utils.PaginationUtils;
import com.zin.jadxaimcp.server.routes.*; // MCP tool call's request handlers

public class PluginServer {
    private static final Logger logger = LoggerFactory.getLogger(PluginServer.class);
    private final MainWindow mainWindow;
    private final int port;
    private Javalin app;
    private final PaginationUtils paginationUtils;
    private volatile boolean isRunning = false;

    /**
     * @param mainWindows - The main Jadx window context
     * @param port        - The port to listen on
     */
    public PluginServer(MainWindow mainWindow, int port) {
        this.mainWindow = mainWindow;
        this.port = port;
        this.paginationUtils = new PaginationUtils();
    }

    /**
     * @return void
     * 
     * This method starts the Javalin HTTP server for the MCP plugin.
     * 1. It creates a Javalin instance with custom configuration:
     *    - Disables the default Javalin banner
     * 2. It starts the server on the configured port
     * 3. It registers all API route handlers via registerRoutes()
     * 4. It sets the running flag to true
     * 5. It logs the startup success message with custom banner and server URL
     * 6. If startup fails, it:
     *    - Logs the error with exception details
     *    - Sets running flag to false
     *    - Re-throws a RuntimeException to notify the plugin
     * 
     * This method is called by the plugin initialization mechanism after
     * JADX has fully loaded the APK content.
     */
    public void start() {
        try {
            // Configure and start Javalin
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
            }).start(port);

            // Register all route handlers
            registerRoutes();

            isRunning = true;

            // Log startup success and banner
            logger.info(JadxAIMCPBanner.banner);
            logger.info("// -------------------- JADX AI MCP PLUGIN -------------------- //");
            logger.info("JADX AI MCP Plugin HTTP Server Started at http://127.0.0.1:" + port + "/");
        
        } catch (Exception e) {
            logger.error("JADX-AI-MCP Plugin Error: Could not start HTTP Server. Exception: " + e.getMessage(), e);
            isRunning = false;
            // Re-throw to let the main plugin know startup failed
            throw new RuntimeException("Failed to start Javalin Server", e);
        }
    }

    /**
     * @return void
     * 
     * This method performs graceful shutdown of the Javalin server.
     * 1. It checks if the server instance exists
     * 2. It calls Javalin's stop() method to close all connections
     * 3. It logs the successful shutdown
     * 4. If shutdown fails, it logs the error
     * 5. In the finally block, it:
     *    - Nullifies the server instance
     *    - Sets running flag to false
     * 
     * This method is called during plugin restart or JADX shutdown.
     */
    public void stop() {
        if (app != null) {
            try {
                app.stop();
                logger.info("JADX-AI-MCP Plugin: HTTP Server Stopped");
            } catch (Exception e) {
                logger.error("JADX-AI-MCP Plugin Error: Error during shutdown: " + e.getMessage(), e);
            } finally {
                app = null;
                isRunning = false;
            }
        }
    }

    /**
     * @return boolean True if server is running, false otherwise
     * 
     * This method returns the volatile running flag indicating server status.
     * The flag is thread-safe and reflects the actual server state.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * @return int The port number the server is configured to listen on
     * 
     * This method returns the port number used by the server.
     * The port is set during construction and remains constant for the server's lifetime.
     */
    public int getPort() {
        return port;
    }

    /**
     * @return void
     * 
     * This method registers all HTTP API endpoints with their route handlers.
     * 1. It instantiates route handler classes, passing required dependencies:
     *    - GeneralRoutes: Health checks and general endpoints
     *    - ClassRoutes: Class navigation and analysis
     *    - MethodRoutes: Method search and retrieval
     *    - ResourceRoutes: Manifest and resource file access
     *    - RefactoringRoutes: Code renaming operations
     *    - DebugRoutes: Debugging information
     *    - XrefsRoutes: Cross-reference analysis
     * 2. It maps HTTP GET endpoints to handler methods organized by category:
     *    - General: /health
     *    - Classes: /current-class, /all-classes, /class-source, etc.
     *    - Methods: /method-by-name, /search-method
     *    - Xrefs: /xrefs-to-class, /xrefs-to-method, /xrefs-to-field
     *    - Resources: /manifest, /strings, /list-all-resource-files-names
     *    - Refactoring: /rename-class, /rename-method, /rename-field, /rename-package
     *    - Debugging: /debug/stack-frames, /debug/variables, /debug/threads
     * 
     * All route handlers receive mainWindow and paginationUtils for accessing
     * JADX API and providing consistent pagination across endpoints.
     */
    private void registerRoutes() {
        // Instantiate Route Controllers
        // Passing 'mainWindow' and 'paginationUtils' to them so they can do their work
        GeneralRoutes generalRoutes = new GeneralRoutes(mainWindow, port, this);
        ClassRoutes classRoutes = new ClassRoutes(mainWindow, paginationUtils);
        MethodRoutes methodRoutes = new MethodRoutes(mainWindow, paginationUtils);
        ResourceRoutes resourceRoutes = new ResourceRoutes(mainWindow);
        RefactoringRoutes refactoringRoutes = new RefactoringRoutes(mainWindow);
        DebugRoutes debugRoutes = new DebugRoutes(mainWindow);
        XrefsRoutes xrefsRoutes = new XrefsRoutes(mainWindow);

        // --- General & Health ---
        app.get("/health", generalRoutes::handleHealth);

        // --- Class & Code Navigation ---
        app.get("/current-class", classRoutes::handleCurrentClass);
        app.get("/all-classes", classRoutes::handleAllClasses);
        app.get("/selected-text", classRoutes::handleSelectedText);
        app.get("/class-source", classRoutes::handleClassSource);
        app.get("/smali-of-class", classRoutes::handleSmaliOfClass);
        app.get("/methods-of-class", classRoutes::handleMethodsOfClass);
        app.get("/fields-of-class", classRoutes::handleFieldsOfClass);
        app.get("/main-application-classes-code", classRoutes::handleMainApplicationClassesCode);
        app.get("/main-application-classes-names", classRoutes::handleMainApplicationClassesNames);
        app.get("/main-activity", classRoutes::handleMainActivity);
        app.get("/search-classes-by-keyword", classRoutes::handleSearchClassesByKeyword);


        // --- Methods ---
        app.get("/method-by-name", methodRoutes::handleMethodByName);
        app.get("/search-method", methodRoutes::handleSearchMethod);
        
        // --- Xrefs ---
        app.get("/xrefs-to-class", xrefsRoutes::handleXrefsToClass);
        app.get("/xrefs-to-method", xrefsRoutes::handleXrefsToMethod);
        app.get("/xrefs-to-field", xrefsRoutes::handleXrefsToField);

        // --- Resources & Manifest ---
        app.get("/manifest", resourceRoutes::handleManifest);
        app.get("/strings", resourceRoutes::handleStrings);
        app.get("/list-all-resource-files-names", resourceRoutes::handleListAllResourceFilesNames);
        app.get("/get-resource-file", resourceRoutes::handleGetResourceFile);

        // --- Renaming ---
        app.get("/rename-class", refactoringRoutes::handleRenameClass);
        app.get("/rename-method", refactoringRoutes::handleRenameMethod);
        app.get("/rename-field", refactoringRoutes::handleRenameField);
        app.get("/rename-package", refactoringRoutes::handleRenamePackage);
        app.get("/rename-variable", refactoringRoutes::handleRenameVariable);
        
        // --- Comments ---
        app.get("/add-comment", refactoringRoutes::handleAddComment);

        // --- Debugging ---
        app.get("/debug/stack-frames", debugRoutes::handleGetStackFrames);
        app.get("/debug/variables", debugRoutes::handleGetVariables);
        app.get("/debug/threads", debugRoutes::handleGetThreads);        
    }

}