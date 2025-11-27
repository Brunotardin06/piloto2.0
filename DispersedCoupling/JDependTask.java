/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.tools.ant.taskdefs.optional.jdepend;

import java.io.File;                        // JDK File: represents directories and files (class directories, output file, etc.)
import java.io.FileWriter;                  // JDK FileWriter: character stream to write the JDepend report file
import java.io.IOException;                 // JDK IOException: signals I/O failures while preparing directories or report
import java.io.PrintWriter;                 // JDK PrintWriter: higher-level writer passed to JDepend for formatted output
import java.lang.reflect.Constructor;       // JDK reflection Constructor: used to instantiate jdepend.framework.PackageFilter
import java.lang.reflect.Method;            // JDK reflection Method: used to invoke JDepend.setFilter(...) if present
import java.util.Vector;                    // JDK Vector: legacy list to hold exclude patterns for PackageFilter
import java.util.Enumeration;               // JDK Enumeration: legacy iterator for environment variables / Vector contents

import org.apache.tools.ant.BuildException; // Ant BuildException: wraps configuration/runtime problems as build failures
import org.apache.tools.ant.Project;        // Ant Project: build context used for logging and property/path resolution
import org.apache.tools.ant.Task;           // Ant Task: base class for custom/optional tasks (like <jdepend>) in build.xml
import org.apache.tools.ant.taskdefs.Execute;               // Ant Execute: helper to fork an external JVM process
import org.apache.tools.ant.taskdefs.ExecuteWatchdog;       // Ant ExecuteWatchdog: kills forked JVM after a timeout
import org.apache.tools.ant.taskdefs.LogStreamHandler;      // Ant LogStreamHandler: routes forked JVM stdout/stderr to Ant log
import org.apache.tools.ant.types.Commandline;              // Ant Commandline: models an OS-level command + arguments
import org.apache.tools.ant.types.CommandlineJava;          // Ant CommandlineJava: models a 'java ...' invocation (VM + args)
import org.apache.tools.ant.types.EnumeratedAttribute;      // Ant EnumeratedAttribute: base type for XML attributes with enums
import org.apache.tools.ant.types.Path;                     // Ant Path: ordered collection of classpath-like filesystem entries
import org.apache.tools.ant.types.PatternSet;               // Ant PatternSet: holds include/exclude patterns for paths/packages
import org.apache.tools.ant.types.Reference;                // Ant Reference: indirect reference to a Path or other Ant datatype
import org.apache.tools.ant.util.FileUtils;                 // Ant FileUtils: utility for safely closing streams and file ops
import org.apache.tools.ant.util.LoaderUtils;               // Ant LoaderUtils: helper to locate resources via class loaders

/**
 * Runs JDepend tests.
 *
 * <p>JDepend is a tool to generate design quality metrics for each Java package.
 * It has been initially created by Mike Clark. JDepend can be found at <a
 * href="http://www.clarkware.com/software/JDepend.html">http://www.clarkware.com/software/JDepend.html</a>.
 *
 * The current implementation spawns a new Java VM (if fork is enabled).
 */
public class JDependTask extends Task {

    //private CommandlineJava commandline = new CommandlineJava();

    // Required attributes (where to read classes/sources from)
    private Path sourcesPath;        // Ant Path: directories with source code – legacy, deprecated in favor of classesPath
    private Path classesPath;        // Ant Path: directories/JARs with .class files to be analyzed by JDepend (preferred)

    // Optional attributes (how JDepend runs and where it writes output)
    private File outputFile;         // Optional output file where the JDepend report will be stored
    private File dir;                // Working directory when invoking JDepend in forked mode
    private Path compileClasspath;   // Extra classpath for JDepend and dependencies when running forked
    private boolean haltonerror = false; // If true, JDepend failure stops the build with BuildException
    private boolean fork = false;         // If true, JDepend runs in a separate JVM; otherwise in-process
    private Long timeout = null;          // Timeout in milliseconds for the forked process (only relevant in fork mode)

    private String jvm = null;            // Custom JVM launcher command (e.g., "java", "javaw") when fork == true
    private String format = "text";       // Report format: "text" (default) or "xml"
    private PatternSet defaultPatterns = new PatternSet(); // Stores <exclude> patterns for packages

    // Optional support for JDepend package filtering via reflection
    private static Constructor packageFilterC; // Constructor for jdepend.framework.PackageFilter(Collection)
    private static Method setFilter;           // Method handle for JDepend.setFilter(PackageFilter) if available

    private boolean includeRuntime = false;    // If true, adds the current process CLASSPATH to the forked JVM
    private Path runtimeClasses = null;        // Path that accumulates runtime classes/JARs to be added for forked execution

    static {
        try {
            // Try to load the PackageFilter API from JDepend (external library)
            Class packageFilter =
                Class.forName("jdepend.framework.PackageFilter");
            packageFilterC =
                packageFilter.getConstructor(new Class[] {java.util.Collection.class});
            setFilter =
                jdepend.textui.JDepend.class.getDeclaredMethod("setFilter",
                                                               new Class[] {packageFilter});
        } catch (Throwable t) {
            // If anything fails, disable filter support gracefully
            if (setFilter == null) {
                packageFilterC = null;
            }
        }
    }

    /**
     * If true, include jdepend.jar in the forked VM, by pulling it from the runtime.
     *
     * @param b include Ant runtime yes or no
     * @since Ant 1.6
     */
    public void setIncluderuntime(boolean b) {
        includeRuntime = b;
    }

    /**
     * Set the timeout value (in milliseconds).
     *
     * <p>If the operation is running for more than this value, the JDepend
     * process will be canceled (only works in fork mode).</p>
     * @param value the maximum time (in milliseconds) allowed before
     * declaring the test as 'timed-out'
     * @see #setFork(boolean)
     */
    public void setTimeout(Long value) {
        timeout = value;
    }

    /**
     * @return the timeout value
     */
    public Long getTimeout() {
        return timeout;
    }

    /**
     * The output file name.
     *
     * @param outputFile the output file name
     */
    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    /**
     * @return the output file name
     */
    public File getOutputFile() {
        return outputFile;
    }

    /**
     * Whether or not to halt on failure. Default: false.
     * @param haltonerror the value to set
     */
    public void setHaltonerror(boolean haltonerror) {
        this.haltonerror = haltonerror;
    }

    /**
     * @return the value of the haltonerror attribute
     */
    public boolean getHaltonerror() {
        return haltonerror;
    }

    /**
     * If true, forks into a new JVM. Default: false.
     *
     * @param   value   true if a JVM should be forked, otherwise false
     */
    public void setFork(boolean value) {
        fork = value;
    }

    /**
     * @return the value of the fork attribute
     */
    public boolean getFork() {
        return fork;
    }

    /**
     * The command used to invoke a forked Java Virtual Machine.
     *
     * Default is "java". Ignored if no JVM is forked.
     * @param   value   the new VM launcher to use instead of "java"
     * @see #setFork(boolean)
     */
    public void setJvm(String value) {
        jvm = value;
    }

    /**
     * Adds a path to source code to analyze.
     * @return a source path
     * @deprecated since Ant 1.6.x – classesPath should be used instead.
     */
    public Path createSourcespath() {
        if (sourcesPath == null) {
            sourcesPath = new Path(getProject());
        }
        return sourcesPath.createPath();
    }

    /**
     * Gets the sources path.
     * @return the sources path
     * @deprecated since Ant 1.6.x – classesPath should be used instead.
     */
    public Path getSourcespath() {
        return sourcesPath;
    }

    /**
     * Adds a path to class files to analyze.
     * @return a classes path
     */
    public Path createClassespath() {
        if (classesPath == null) {
            classesPath = new Path(getProject());
        }
        return classesPath.createPath();
    }

    /**
     * Gets the classes path.
     * @return the classes path
     */
    public Path getClassespath() {
        return classesPath;
    }

    /**
     * The directory to invoke the VM in. Ignored if no JVM is forked.
     * @param   dir     the directory to invoke the JVM from.
     * @see #setFork(boolean)
     */
    public void setDir(File dir) {
        this.dir = dir;
    }

    /**
     * @return the dir attribute
     */
    public File getDir() {
        return dir;
    }

    /**
     * Set the classpath to be used for this execution.
     * @param classpath a class path to be used
     */
    public void setClasspath(Path classpath) {
        if (compileClasspath == null) {
            compileClasspath = classpath;
        } else {
            compileClasspath.append(classpath);
        }
    }

    /**
     * Gets the classpath to be used for this execution.
     * @return the class path used for JDepend
     */
    public Path getClasspath() {
        return compileClasspath;
    }

    /**
     * Adds a path to the classpath.
     * @return a classpath
     */
    public Path createClasspath() {
        if (compileClasspath == null) {
            compileClasspath = new Path(getProject());
        }
        return compileClasspath.createPath();
    }

    /**
     * Create a new JVM argument. Ignored if no JVM is forked.
     * @param commandline the commandline to create the argument on
     * @return a JVM argument (e.g., -Xmx, -classpath)
     * @see #setFork(boolean)
     */
    public Commandline.Argument createJvmarg(CommandlineJava commandline) {
        return commandline.createVmArgument();
    }

    /**
     * Adds a reference to a classpath defined elsewhere.
     * @param r a classpath reference
     */
    public void setClasspathRef(Reference r) {
        createClasspath().setRefid(r);
    }

    /**
     * Add a name entry on the exclude list.
     * @return a pattern entry for the excludes
     */
    public PatternSet.NameEntry createExclude() {
        return defaultPatterns.createExclude();
    }

    /**
     * @return the excludes patterns
     */
    public PatternSet getExcludes() {
        return defaultPatterns;
    }

    /**
     * The format to write the output in, "xml" or "text".
     *
     * @param ea xml or text
     */
    public void setFormat(FormatAttribute ea) {
        format = ea.getValue();
    }

    /**
     * A class for the enumerated attribute "format", values are "xml" and "text".
     * @see EnumeratedAttribute
     */
    public static class FormatAttribute extends EnumeratedAttribute {
        private String [] formats = new String[]{"xml", "text"};

        /**
         * @return the enumerated values
         */
        public String[] getValues() {
            return formats;
        }
    }

    /**
     * No problems with this test.
     */
    private static final int SUCCESS = 0; // Exit code for success
    /**
     * An error occurred.
     */
    private static final int ERRORS = 1;  // Exit code for errors

    /**
     * Search for the given resource and add the directory or archive
     * that contains it to the classpath.
     *
     * <p>Doesn't work for archives in JDK 1.1 as the URL returned by
     * getResource doesn't contain the name of the archive.</p>
     *
     * @param resource resource that one wants to lookup
     * @since Ant 1.6
     */
    private void addClasspathEntry(String resource) {
        /*
         * Pre Ant 1.6 this method used to call getClass().getResource
         * while Ant 1.6 will call ClassLoader.getResource().
         *
         * The difference is that Class.getResource expects a leading
         * slash for "absolute" resources and will strip it before
         * delegating to ClassLoader.getResource - so we now have to
         * emulate Class's behavior.
         */
        if (resource.startsWith("/")) {
            resource = resource.substring(1);
        } else {
            resource = "org/apache/tools/ant/taskdefs/optional/jdepend/"
                + resource;
        }

        // Delegates to LoaderUtils (external Ant util) to resolve the physical container of the JDepend class
        File f = LoaderUtils.getResourceSource(getClass().getClassLoader(),
                                               resource);
        if (f != null) {
            log("Found " + f.getAbsolutePath(), Project.MSG_DEBUG);
            // The resolved file/JAR is added to runtimeClasses, later used when configuring the forked JVM classpath
            runtimeClasses.createPath().setLocation(f);
        } else {
            log("Couldn't find " + resource, Project.MSG_DEBUG);
        }
    }

    /**
     * Execute the task.
     *
     * @exception BuildException if an error occurs
     */
    public void execute() throws BuildException {

        CommandlineJava commandline = new CommandlineJava(); // Command model for "java ..." when running forked

        // Here the task selects which external JDepend main class will be used when running in forked mode
        if ("text".equals(format)) {
            commandline.setClassname("jdepend.textui.JDepend");
        } else if ("xml".equals(format)) {
            commandline.setClassname("jdepend.xmlui.JDepend");
        }

        if (jvm != null) {
            commandline.setVm(jvm);
        }
        if (getSourcespath() == null && getClassespath() == null) {
            throw new BuildException("Missing classespath required argument");
        } else if (getClassespath() == null) {
            String msg =
                "sourcespath is deprecated in JDepend >= 2.5 "
                + "- please convert to classespath";
            log(msg);
        }

        // Execute the test and get the return code
        int exitValue = JDependTask.ERRORS;
        boolean wasKilled = false;
        if (!getFork()) {
            // In-process execution: JDepend runs inside the same JVM as Ant (direct API usage)
            exitValue = executeInVM(commandline);
        } else {
            // Forked execution: JDepend runs in a separate JVM (via Execute/ExecuteWatchdog)
            ExecuteWatchdog watchdog = createWatchdog();
            exitValue = executeAsForked(commandline, watchdog);
            // Null watchdog means no timeout; only check if watchdog was created
            if (watchdog != null) {
                wasKilled = watchdog.killedProcess();
            }
        }

        // Decide whether an error occurred (exit code or timeout)
        boolean errorOccurred = exitValue == JDependTask.ERRORS || wasKilled;

        if (errorOccurred) {
            String errorMessage = "JDepend FAILED"
                + (wasKilled ? " - Timed out" : "");

            if  (getHaltonerror()) {
                // Fatal mode: fail the whole build
                throw new BuildException(errorMessage, getLocation());
            } else {
                // Non-fatal mode: just log an error
                log(errorMessage, Project.MSG_ERR);
            }
        }
    }

    // Comment from JUnit Task also applies here:
    // "in VM is not very nice since it could probably hang the
    // whole build. IMHO this method should be avoided and it would be best
    // to remove it in future versions. TBD. (SBa)"

    /**
     * Execute inside the current JVM.
     *
     * @param commandline the command line descriptor (used mostly for consistency)
     * @return the return value of the in-VM execution
     * @exception BuildException if an error occurs
     */
    public int executeInVM(CommandlineJava commandline) throws BuildException {
        jdepend.textui.JDepend jdepend;

        // Directly instantiates the external JDepend runner (text or XML UI) according to the chosen format
        if ("xml".equals(format)) {
            jdepend = new jdepend.xmlui.JDepend();
        } else {
            jdepend = new jdepend.textui.JDepend();
        }

        FileWriter fw = null; // Optional writer for file output
        if (getOutputFile() != null) {
            try {
                fw = new FileWriter(getOutputFile().getPath());
            } catch (IOException e) {
                String msg = "JDepend Failed when creating the output file: "
                    + e.getMessage();
                log(msg);
                throw new BuildException(msg);
            }
            // JDepend will write its report through this PrintWriter instead of stdout
            jdepend.setWriter(new PrintWriter(fw));
            log("Output to be stored in " + getOutputFile().getPath());
        }

        try {
            if (getClassespath() != null) {
                // Preferred path: analyze compiled classes from classesPath
                String[] cP = getClassespath().list();
                for (int i = 0; i < cP.length; i++) {
                    File f = new File(cP[i]);
                    // Quick validation to avoid obscure JDepend errors
                    if (!f.exists()) {
                        String msg = "\""
                            + f.getPath()
                            + "\" does not represent a valid"
                            + " file or directory. JDepend would fail.";
                        log(msg);
                        throw new BuildException(msg);
                    }
                    try {
                        // Each valid directory/JAR is handed over to the JDepend API as an analysis root
                        jdepend.addDirectory(f.getPath());
                    } catch (IOException e) {
                        String msg =
                            "JDepend Failed when adding a class directory: "
                            + e.getMessage();
                        log(msg);
                        throw new BuildException(msg);
                    }
                }

            } else if (getSourcespath() != null) {

                // Legacy path: analyze source directories from sourcesPath
                String[] sP = getSourcespath().list();
                for (int i = 0; i < sP.length; i++) {
                    File f = new File(sP[i]);

                    // Quick validation before invoking JDepend
                    if (!f.exists() || !f.isDirectory()) {
                        String msg = "\""
                            + f.getPath()
                            + "\" does not represent a valid"
                            + " directory. JDepend would fail.";
                        log(msg);
                        throw new BuildException(msg);
                    }
                    try {
                        // Source directories are also registered with JDepend as analysis roots
                        jdepend.addDirectory(f.getPath());
                    } catch (IOException e) {
                        String msg =
                            "JDepend Failed when adding a source directory: "
                            + e.getMessage();
                        log(msg);
                        throw new BuildException(msg);
                    }
                }
            }

            // Convert <exclude> tags into patterns for the JDepend filter
            String[] patterns = defaultPatterns.getExcludePatterns(getProject());
            if (patterns != null && patterns.length > 0) {
                if (setFilter != null) {
                    Vector v = new Vector();
                    for (int i = 0; i < patterns.length; i++) {
                        v.addElement(patterns[i]);
                    }
                    try {
                        // Builds a JDepend PackageFilter instance via reflection and applies it
                        Object o = packageFilterC.newInstance(new Object[] {v});
                        setFilter.invoke(jdepend, new Object[] {o});
                    } catch (Throwable e) {
                        log("Excludes will be ignored as JDepend doesn't like me: "
                            + e.getMessage(), Project.MSG_WARN);
                    }
                } else {
                    // Older JDepend versions do not support excludes
                    log("Sorry, your version of JDepend doesn't support excludes",
                        Project.MSG_WARN);
                }
            }

            // Delegates to JDepend's analyze() method, which performs the package analysis and writes the report
            jdepend.analyze();
        } finally {
            FileUtils.close(fw);
        }
        return SUCCESS;
    }


    /**
     * Execute the task by forking a new JVM. The call blocks until the process finishes.
     * To know if the process was destroyed by timeout, use killedProcess() on the watchdog.
     * @param commandline the commandline for the forked JVM
     * @param watchdog    the watchdog that cancels the test if it exceeds the configured time (may be null)
     * @return the result of running JDepend (its exit code)
     * @throws BuildException in case of error
     */
    public int executeAsForked(CommandlineJava commandline,
                               ExecuteWatchdog watchdog) throws BuildException {
        runtimeClasses = new Path(getProject()); // Path that accumulates runtime classes/JARs
        // Uses addClasspathEntry to discover where the external JDepend classes live and include them for the forked JVM
        addClasspathEntry("/jdepend/textui/JDepend.class");

        // Ensure we have a Path for the classpath even if not explicitly set
        createClasspath();

        // If there is any configured classpath, wire it into the JVM args
        if (getClasspath().toString().length() > 0) {
            createJvmarg(commandline).setValue("-classpath");
            createJvmarg(commandline).setValue(getClasspath().toString());
        }

        if (includeRuntime) {
            // Merge CLASSPATH from the current process into the forked JDepend classpath
            Vector v = Execute.getProcEnvironment();
            Enumeration e = v.elements();
            while (e.hasMoreElements()) {
                String s = (String) e.nextElement();
                if (s.startsWith("CLASSPATH=")) {
                    commandline.createClasspath(getProject()).createPath()
                        .append(new Path(getProject(),
                                         s.substring("CLASSPATH=".length()
                                                     )));
                }
            }
            log("Implicitly adding " + runtimeClasses + " to CLASSPATH",
                Project.MSG_VERBOSE);
            commandline.createClasspath(getProject()).createPath()
                .append(runtimeClasses);
        }

        if (getOutputFile() != null) {
            // Split "-file <path>" into separate arguments to avoid quoting issues
            commandline.createArgument().setValue("-file");
            commandline.createArgument().setValue(outputFile.getPath());
        }

        if (getSourcespath() != null) {
            // Legacy: pass source directories as arguments to JDepend CLI
            String[] sP = getSourcespath().list();
            for (int i = 0; i < sP.length; i++) {
                File f = new File(sP[i]);

                if (!f.exists() || !f.isDirectory()) {
                    throw new BuildException("\"" + f.getPath()
                                             + "\" does not represent a valid"
                                             + " directory. JDepend would"
                                             + " fail.");
                }
                commandline.createArgument().setValue(f.getPath());
            }
        }

        if (getClassespath() != null) {
            // Preferred: pass class directories/JARs as arguments to JDepend CLI
            String[] cP = getClassespath().list();
            for (int i = 0; i < cP.length; i++) {
                File f = new File(cP[i]);
                if (!f.exists()) {
                    throw new BuildException("\"" + f.getPath()
                                             + "\" does not represent a valid"
                                             + " file or directory. JDepend would"
                                             + " fail.");
                }
                commandline.createArgument().setValue(f.getPath());
            }
        }

        // Execute encapsulates process creation and delegates actual JDepend execution to an external JVM process
        Execute execute = new Execute(new LogStreamHandler(this,
            Project.MSG_INFO, Project.MSG_WARN), watchdog);
        execute.setCommandline(commandline.getCommandline());
        if (getDir() != null) {
            execute.setWorkingDirectory(getDir());
            execute.setAntRun(getProject());
        }

        if (getOutputFile() != null) {
            log("Output to be stored in " + getOutputFile().getPath());
        }
        log(commandline.describeCommand(), Project.MSG_VERBOSE);
        try {
            return execute.execute();
        } catch (IOException e) {
            throw new BuildException("Process fork failed.", e, getLocation());
        }
    }

    /**
     * Create a watchdog based on the configured timeout.
     *
     * @return null if there is no timeout value, otherwise a watchdog instance.
     * @throws BuildException in case of error
     */
    protected ExecuteWatchdog createWatchdog() throws BuildException {
        if (getTimeout() == null) {
            return null;
        }
        // Wraps the external process with an ExecuteWatchdog so that long-running JDepend runs can be aborted
        return new ExecuteWatchdog(getTimeout().longValue());
    }
}
