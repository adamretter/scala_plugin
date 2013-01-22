package htd.jnkns;

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
import hudson.Util;

import java.io.IOException;
import java.io.File;
import java.util.Iterator;
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
	public ScalaBuilder(String libDir, String src, String pathToScala,
			boolean debug, String port, boolean suspend) {
		this.libDir = libDir;
		this.src = src;
		this.debug = debug;
		this.port = port;
		if (this.debug && this.port.equals("")) {
			System.out
					.println("Warning, setting to randomly generated port as none specified");
			this.port = "0";
		}
		this.suspend = suspend;
		if (pathToScala.equals(""))
			this.pathToScala = "scala";
		else
			this.pathToScala = pathToScala;
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

	public void setDebug(boolean setDebug) {
		this.debug = setDebug;
	}

	public void setLibDir(String libDir) {
		this.libDir = libDir;
	}

	public void setSrc(String src) {
		this.src = src;
	}

	public void setPort(String port) {
		this.port = port;
	}

	public void setSuspend(boolean suspend) {
		this.suspend = suspend;
	}

	public void setPathToScala(String pathToScala) {
		this.pathToScala = pathToScala;
	}

	/**
	 * Creates a scala script file in a temporary name in the specified
	 * directory.
	 */
	private FilePath createScriptFile(FilePath dir) throws IOException,
			InterruptedException {
		return dir.createTextTempFile("jenkins", ".scala",
				"println(\"Running user source...\");" + src, false);
	}

	private String getClasspathStringFromDir(String dir, boolean isUnix) {
		if (dir == "")
			return "";
		String separator = isUnix ? ":" : ";";
		// build classpath from files copied
		StringBuilder full_classpath_builder = new StringBuilder();
		String[] dirs = dir.split(";");
		for (int i = 0; i < dirs.length; ++i) {
			String[] libFileList = new File(dirs[i]).list();
			if (libFileList == null)
				continue; // Should never get here as the directories are
							// verified in the form
			if (libFileList.length > 0) {
				for (int j = 0; j < libFileList.length; ++j) {
					if (libFileList[j].endsWith(".jar"))
						full_classpath_builder.append("jars/"+libFileList[j]
								+ separator);
				}
			}
		}
		if (full_classpath_builder.toString().length() > 0)
			return " -cp " + full_classpath_builder.toString();
		else
			return "";
	}

	// Expand any environment variables referred to in the passed string
	private String expandLibDir(String str, EnvVars env) {
		String expanded = str;
		for (Iterator<String> itr = env.keySet().iterator(); itr.hasNext();) {
			String key = itr.next();
			// System.out.println("key: "+key + " value "+env.get(key));
			expanded = expanded.replace("${" + key + "}", env.get(key));
		}
		return expanded;
	}

	public boolean copyDirectory(AbstractBuild<?, ?> build, BuildListener listener, FilePath from,
			FilePath to, String path) {
		listener.getLogger().println("Copying directory contents: " + path);
		try {
			new FilePath(build.getWorkspace().getChannel(), to.getRemote() + "/jars").mkdirs();
			List<FilePath> files = new FilePath(new File(from.getRemote()
					+ path)).list();
			for (Iterator<FilePath> iter = files.iterator(); iter.hasNext();) {
				FilePath fl = iter.next();
				if (fl.isDirectory())
					copyDirectory(build, listener, from, to,
							path + "/" + fl.getName());
				else if (fl.getName().endsWith(".jar")) {
					// Copy if changed (altered size/timestamp)
					FilePath existingFl = new FilePath(build.getWorkspace().getChannel(), to.getRemote() + "/jars" + path + "/" + fl.getName());
					FilePath newFl = new FilePath(new File(from.getRemote() + path + "/" + fl.getName()));
					if (!existingFl.exists()) {
						//listener.getLogger().println("File doesnt already exist: " + fl.getName() + ", copying");
						newFl.copyToWithPermission(existingFl);
					} else {
						// get file sizes
						/*listener.getLogger().println("Existing file length: "+existingFl.getRemote() + " is " + existingFl.length());
						listener.getLogger().println("New      file length: "+newFl.getRemote() + " is " + newFl.length());
						listener.getLogger().println("Existing file last modified: "+existingFl.getRemote() + " is " + existingFl.lastModified() + " " + new Date(existingFl.lastModified()));
						listener.getLogger().println("New      file last modified: "+newFl.getRemote() + " is " + newFl.lastModified() + " " + new Date(newFl.lastModified()));*/
						if (existingFl.length() == newFl.length() && existingFl.lastModified() / 1000 == newFl.lastModified() / 1000) // strip milliseconds
						{ /*listener.getLogger().println("File not copied - same as existing: "	+ fl.getName());*/  } 
						else {
							//listener.getLogger().println("File has changed: " + fl.getName() + ", copying");
							newFl.copyToWithPermission(existingFl);
						}
					}
				}
			}
		} catch (Throwable ex) {
			listener.getLogger().println(
					"Failed to copy one or more files in directory: " + path + ", exception: "+ex.getMessage());
			ex.printStackTrace();
		}

		return false;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {

		// Get source/dest directories for library file copy
		FilePath projectWorkspaceOnSlave = build.getWorkspace(); 

		EnvVars env = build.getEnvironment(listener);
		String expandedLibDir = expandLibDir(libDir, env);
		String[] dirs = expandedLibDir.split(";");
		for (int i = 0; i < dirs.length; ++i) {
			FilePath dir = new FilePath(new File(dirs[i]));
			if (dir.exists() && !dir.equals("")) {
				listener.getLogger().println(
						"Directory specified by user for libraries to copy exists, copying files from "
								+ dir + " to "
								+ projectWorkspaceOnSlave.getRemote());
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
		FilePath src_tmp = createScriptFile(build.getWorkspace());
		// Get a string to use as a scala classpath (*nix only)
		String full_classpath = getClasspathStringFromDir(expandedLibDir,
				launcher.isUnix());

		String debugOptionsStr = debug ? (" -J-debug -J-Xrunjdwp:transport=dt_socket,server=y,suspend="
				+ (suspend ? "y" : "n") + ",address=" + port)
				: "";

		// TODO: swap for // SCALA_HOME!!!! windows? or write scala tool
		// installer plugin
		String scala_launch_cmd = new String(pathToScala + " -nocompdaemon "
				+ full_classpath.toString() + " " + src_tmp.getRemote()
				+ debugOptionsStr);

		// TODO: Let user specify scala command line options
		Shell shell = new Shell(scala_launch_cmd);
		listener.getLogger().println(
				"Scala launch command is: " + scala_launch_cmd);
		Boolean result = shell.perform(build, launcher, listener);

		// Cleanup - delete temporary scala source file
		src_tmp.delete();
		return result;
	}

	public static Computer findSlaveNode(String nodeName) {
		for (Computer currentNode : Hudson.getInstance().getComputers()) {
			if (currentNode.getDisplayName().equals(nodeName)) {
				return currentNode;
			}
		}
		return null;
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return "Execute scala script";
		}

		public FormValidation doCheckLibDir(StaplerRequest req,
				@AncestorInPath AbstractProject context,
				@QueryParameter String value) {
			String[] dirs = value.split(";");
			if (dirs.length == 0)
				return FormValidation.ok();
			if (dirs.length == 1 && dirs[0].equals(""))
				return FormValidation.ok();
			for (int i = 0; i < dirs.length; ++i)
				if (!new File(dirs[i]).exists())
					return FormValidation
							.error("Cannot detect directory: "
									+ dirs[i]
									+ ", please check it exists on the Jenkins server (not the execution node!)");

			return FormValidation.ok();
		}

		@Override
		public Builder newInstance(StaplerRequest req, JSONObject formData)
				throws hudson.model.Descriptor.FormException {
			// this will be null if debug checkbox has not been selected, which
			// decides what we instantiate ScalaBuilder with
			JSONObject s = formData.getJSONObject("debug");
			// user hasnt selected to debug, just pass in src and path info
			if (s.isNullObject())
				return new ScalaBuilder(formData.getString("libDir"),
						formData.getString("src"),
						formData.getString("pathToScala"), false, null, false);
			// user wants to debug, get port/suspend info
			else
				return new ScalaBuilder(formData.getString("libDir"),
						formData.getString("src"),
						formData.getString("pathToScala"), true,
						s.getString("port"), s.getBoolean("suspend"));
			// return req.bindJSON(clazz, formData);
		}
	}
}
