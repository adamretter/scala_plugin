package hudson.plugins.scalabuilder;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.FilePath;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import hudson.util.FormValidation;
//import hudson.Util;

import java.io.IOException;
import java.io.File;
import java.util.List;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class ScalaBuilder extends Builder {

    private boolean debug;
    private String libDir;
    private String src;
    private String port;
    private boolean suspend;
    private String pathToScala;

    @DataBoundConstructor
    public ScalaBuilder(final String libDir, final String src, 
        final String pathToScala, final boolean debug, final String port,
        final boolean suspend) {
            
        this.libDir = libDir;
        this.src = src;
        this.debug = debug;
        this.port = port;
        
        if(this.debug && this.port.isEmpty()) {
            System.out.println("Warning, setting to randomly generated port as none specified");
            this.port = "0";
        }
        
        this.suspend = suspend;
        
        if(pathToScala.isEmpty()) {
            this.pathToScala = "scala";
        } else {
            this.pathToScala = pathToScala;
        }
    }

    public boolean getDebug() {
        return debug;
    }

    public String getLibDir() {
        return libDir;
    }

    public String getSrc() {
        return src;
    }

    public String getPort() {
        return port;
    }

    public boolean getSuspend() {
        return suspend;
    }

    public String getPathToScala() {
        return pathToScala;
    }

    public void setDebug(final boolean setDebug) {
        this.debug = setDebug;
    }

    public void setLibDir(final String libDir) {
        this.libDir = libDir;
    }

    public void setSrc(final String src) {
        this.src = src;
    }

    public void setPort(final String port) {
        this.port = port;
    }

    public void setSuspend(final boolean suspend) {
            this.suspend = suspend;
    }

    public void setPathToScala(final String pathToScala) {
        this.pathToScala = pathToScala;
    }

    /**
     * Creates a scala script file in a temporary name in the specified
     * directory.
     */
    private FilePath createScriptFile(final FilePath dir) throws IOException,
        InterruptedException {
        
        return dir.createTextTempFile("jenkins", ".scala",
            "println(\"Running user source...\");" + src, false);
    }

    private String getClasspathStringFromDir(final String dir, final boolean isUnix) {
        if(dir.isEmpty()) {
            return "";
        }
            
        final char separator = isUnix ? ':' : ';';
        
        // build classpath from files copied
        final StringBuilder full_classpath_builder = new StringBuilder();
        final String[] dirs = dir.split(";");
        for(final String d : dirs) {
            final String[] libFileList = new File(d).list();
            if(libFileList == null) {
                /*
                 * Should never get here as the directories are
                 * verified in the form
                 */
                continue;
            }
            
            if(libFileList.length > 0) {
                for(String libFile : libFileList) {
                    if(libFile.endsWith(".jar")) {
                        full_classpath_builder.append("jars/");
                        full_classpath_builder.append(libFile);
                        full_classpath_builder.append(separator);
                    }
                }
            }
        }
        
        final String full_classpath = full_classpath_builder.toString();
                
        if(!full_classpath.isEmpty()) {
            return String.format(" -cp %s", full_classpath_builder.toString());
        } else {
            return full_classpath;
        }
    }

    // Expand any environment variables referred to in the passed string
    private String expandLibDir(final String str, final EnvVars env) {
        String expanded = str;
        for(final String key : env.keySet()) {
            // System.out.println("key: "+key + " value "+env.get(key));
            expanded = expanded.replace("${" + key + "}", env.get(key));
        }
        return expanded;
    }

    public void copyDirectory(final AbstractBuild<?, ?> build,
        final BuildListener listener, final FilePath from,
        final FilePath to, final String path) {

        listener.getLogger().println("Copying directory contents: " + path);

        try {
            new FilePath(build.getWorkspace().getChannel(), to.getRemote() + "/jars").mkdirs();
            final List<FilePath> files = new FilePath(new File(from.getRemote() + path)).list();
            for(final FilePath fl :files) {
                if(fl.isDirectory()) {
                    copyDirectory(build, listener, from, to, path + "/" + fl.getName());
                } else if(fl.getName().endsWith(".jar")) {
                    // Copy if changed (altered size/timestamp)
                    final FilePath existingFl = new FilePath(build.getWorkspace().getChannel(), to.getRemote() + "/jars" + path + "/" + fl.getName());
                    final FilePath newFl = new FilePath(new File(from.getRemote() + path + "/" + fl.getName()));
                    if(!existingFl.exists()) {
                        //listener.getLogger().println("File doesnt already exist: " + fl.getName() + ", copying");
                        newFl.copyToWithPermission(existingFl);
                    } else {
                        // get file sizes
                        /*listener.getLogger().println("Existing file length: "+existingFl.getRemote() + " is " + existingFl.length());
                        listener.getLogger().println("New      file length: "+newFl.getRemote() + " is " + newFl.length());
                        listener.getLogger().println("Existing file last modified: "+existingFl.getRemote() + " is " + existingFl.lastModified() + " " + new Date(existingFl.lastModified()));
                        listener.getLogger().println("New      file last modified: "+newFl.getRemote() + " is " + newFl.lastModified() + " " + new Date(newFl.lastModified()));*/
                        if(existingFl.length() == newFl.length() && existingFl.lastModified() / 1000 == newFl.lastModified() / 1000) { // strip milliseconds
                            /*listener.getLogger().println("File not copied - same as existing: "	+ fl.getName());*/
                        } else {
                            //listener.getLogger().println("File has changed: " + fl.getName() + ", copying");
                            newFl.copyToWithPermission(existingFl);
                        }
                    }
                }
            }
        } catch(final Throwable ex) {
            listener.getLogger().println("Failed to copy one or more files in directory: "
                + path + ", exception: "+ex.getMessage());
            ex.printStackTrace();
        }
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher,
        final BuildListener listener) throws InterruptedException, IOException {

        // Get source/dest directories for library file copy
        final FilePath projectWorkspaceOnSlave = build.getWorkspace(); 

        final EnvVars env = build.getEnvironment(listener);
        final String expandedLibDir = expandLibDir(libDir, env);
        final String[] dirs = expandedLibDir.split(";");
        for(final String d : dirs) {
            final FilePath dir = new FilePath(new File(d));
            if(dir.exists()) {
            listener.getLogger().println("Directory specified by user for"
                + "libraries to copy exists, copying files from " + dir
                + " to " + projectWorkspaceOnSlave.getRemote());
            copyDirectory(build, listener, dir, projectWorkspaceOnSlave, "");
            /*
             * listener.getLogger().println( "Number of files copied: " +
             * dir.copyRecursiveTo("*.jar", projectWorkspaceOnSlave));
             */
            }
        }

        // launcher.launch().cmds("echo",
        // "blah").stdout(listener.getLogger()).pwd(build.getWorkspace()).join();
        // Shell shell = new Shell("echo hello");
        // shell.perform(build, launcher, listener);

        // Write the scala source to file
        FilePath src_tmp = null;
        try {
            src_tmp = createScriptFile(build.getWorkspace());


            // Get a string to use as a scala classpath (*nix only)
            final String full_classpath = getClasspathStringFromDir(expandedLibDir,
                launcher.isUnix());

            final String debugOptionsStr = debug ? (" -J-debug -J-Xrunjdwp:transport=dt_socket,server=y,suspend="
                + (suspend ? "y" : "n") + ",address=" + port) : "";

            // TODO: swap for // SCALA_HOME!!!! windows? or write scala tool
            // installer plugin
            final String scala_launch_cmd = pathToScala + " -nocompdaemon "
                + full_classpath + " " + src_tmp.getRemote() + debugOptionsStr;

            // TODO: Let user specify scala command line options
            final Shell shell = new Shell(scala_launch_cmd);
            listener.getLogger().println("Scala launch command is: "
                + scala_launch_cmd);

            return shell.perform(build, launcher, listener);
            
        } finally {
            // Cleanup - delete temporary scala source file
            if(src_tmp != null) {
                src_tmp.delete();
            }
        }
    }

    public static Computer findSlaveNode(final String nodeName) {
        Computer slaveNode = null;
        for(final Computer currentNode : Hudson.getInstance().getComputers()) {
            if(currentNode.getDisplayName().equals(nodeName)) {
                slaveNode = currentNode;
                break;
            }
        }
        return slaveNode;
    }

    @Extension
    public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @Override
        public boolean isApplicable(final Class<? extends AbstractProject> jobType) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Execute Scala script";
        }

        public FormValidation doCheckLibDir(final StaplerRequest req,
            @AncestorInPath final AbstractProject context,
            @QueryParameter final String value) {
            
            final String[] dirs = value.split(";");
            if(dirs.length == 0) {
                return FormValidation.ok();
            } else  if (dirs.length == 1 && dirs[0].isEmpty()) {
                return FormValidation.ok();
            }
                
            for(final String dir : dirs) {
                if(!new File(dir).exists()) {
                    return FormValidation.error("Cannot detect directory: "
                        + dir
                        + ", please check it exists on the Jenkins server (not the execution node!)");
                }
            }
            
            return FormValidation.ok();
        }

        @Override
        public Builder newInstance(final StaplerRequest req,
            final JSONObject formData) throws hudson.model.Descriptor.FormException {
            // this will be null if debug checkbox has not been selected, which
            // decides what we instantiate ScalaBuilder with
            final JSONObject s = formData.getJSONObject("debug");
            // user hasnt selected to debug, just pass in src and path info
            
            final Builder builder;
            if(s.isNullObject()) {
                builder = new ScalaBuilder(formData.getString("libDir"),
                    formData.getString("src"),
                    formData.getString("pathToScala"), false, null, false);
            } else {
                // user wants to debug, get port/suspend info
                builder = new ScalaBuilder(formData.getString("libDir"),
                    formData.getString("src"),
                    formData.getString("pathToScala"), true,
                    s.getString("port"), s.getBoolean("suspend"));
                // return req.bindJSON(clazz, formData);
            }
            return builder;
        }
    }
}
