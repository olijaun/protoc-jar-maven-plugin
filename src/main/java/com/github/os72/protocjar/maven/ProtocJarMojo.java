/*
 * Copyright 2014 protoc-jar developers
 * 
 * Incorporates code derived from https://github.com/igor-petruk/protobuf-maven-plugin
 * Copyright 2012, by Yet another Protobuf Maven Plugin Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.os72.protocjar.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.sonatype.plexus.build.incremental.BuildContext;

import com.github.os72.protocjar.PlatformDetector;
import com.github.os72.protocjar.Protoc;
import com.github.os72.protocjar.ProtocVersion;

/**
 * Compiles .proto files using protoc-jar embedded protoc compiler. Also supports pre-installed protoc binary, and downloading binaries (protoc and protoc plugins) from maven repo
 * 
 * @goal run
 * @phase generate-sources
 * @requiresDependencyResolution
 */
public class ProtocJarMojo extends AbstractMojo
{
	private static final String DEFAULT_INPUT_DIR = "/src/main/protobuf/".replace('/', File.separatorChar);

	/**
	 * Specifies the protoc version (default: latest version).
	 * 
	 * @parameter property="protocVersion"
	 */
	private String protocVersion;

	/**
	 * If "true", skip code generation when .proto input files appear unchanged since last compilation.
	 * 
	 * @parameter property="optimizeCodegen" default-value="true"
	 */
	private boolean optimizeCodegen;

	/**
	 * Input directories that have *.proto files (or the configured extension).
	 * If none specified then <b>src/main/protobuf</b> is used.
	 * 
	 * @parameter property="inputDirectories"
	 */
	private File[] inputDirectories;

	/**
	 * This parameter lets you specify additional include paths to protoc.
	 * 
	 * @parameter property="includeDirectories"
	 */
	private File[] includeDirectories;

	/**
	 * If "true", extract the included google.protobuf standard types and add them to protoc import path.
	 * 
	 * @parameter property="includeStdTypes" default-value="false"
	 */
	private boolean includeStdTypes;

	/**
	 * Specifies whether to extract .proto files from Maven dependencies and add them to the protoc import path.
	 * Options: "none" (do not extract any proto files), "direct" (extract only from direct dependencies),
	 * "transitive" (extract from direct and transitive dependencies)
	 *
	 * @parameter property="includeMavenTypes" default-value="none"
	 */
	private String includeMavenTypes;

	/**
	 * Specifies whether to compile .proto files from Maven dependencies.
	 * Options: "none" (do not extract any proto files), "direct" (compile only direct dependencies),
	 * "transitive" (compile direct and transitive dependencies).
	 *
	 * @parameter property="compileMavenTypes" default-value="none"
	 */
	private String compileMavenTypes;

	/**
	 * Specifies whether to add the source .proto files found in inputDirectories/includeDirectories to the generated jar file.
	 * Options: "all" (add all), "inputs" (add only from inputDirectories), "none" (default: "none")
	 * 
	 * @parameter property="addProtoSources" default-value="none"
	 */
	private String addProtoSources;

	/**
	 * Specifies output type.
	 * Options: "java",  "cpp", "python", "descriptor" (default: "java"); for proto3 also: "javanano", "csharp", "objc", "ruby", "js"
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="type" default-value="java"
	 */
	private String type;

	/**
	 * Specifies whether to add outputDirectory to sources that are going to be compiled.
	 * Options: "main", "test", "none" (default: "main")
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="addSources" default-value="main"
	 */
	private String addSources;

	/**
	 * Specifies whether to include all dependencies of the input files in the descriptor set
	 * Options: "true", "false" (default: "true")
	 * <p>
	 * Ignored when the {@code type} of {@code <outputTarget>} is not {@code descriptor}
	 *
	 * @parameter property="includeImports" default-value="true"
	 */
	private boolean includeImports;

	/**
	 * If this parameter is set to "true" output folder is cleaned prior to the
	 * build. This will not let old and new classes coexist after package or
	 * class rename in your IDE cache or after non-clean rebuild. Set this to
	 * "false" if you are doing multiple plugin invocations per build and it is
	 * important to preserve output folder contents
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="cleanOutputFolder" default-value="false"
	 */
	private boolean cleanOutputFolder;

	/**
	 * Path to the protoc plugin that generates code for the specified {@link #type}.
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 *
	 * @parameter property="pluginPath"
	 */
	private String pluginPath;

	/**
	 * Maven artifact coordinates of the protoc plugin that generates code for the specified {@link #type}.
	 * Format: "groupId:artifactId:version" (eg, "io.grpc:protoc-gen-grpc-java:1.0.1")
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 *
	 * @parameter property="pluginArtifact"
	 */
	private String pluginArtifact;

	/**
	 * Output directory for the generated java files. Defaults to
	 * "${project.build.directory}/generated-sources" or
	 * "${project.build.directory}/generated-test-sources"
	 * depending on the addSources parameter
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 * 
	 * @parameter property="outputDirectory"
	 */
	private File outputDirectory;

	/**
	 * If this parameter is set, append its value to the {@link #outputDirectory} path
	 * For example, value "protobuf" will set output directory to
	 * "${project.build.directory}/generated-sources/protobuf" or
	 * "${project.build.directory}/generated-test-sources/protobuf"
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 *
	 * @parameter property="outputDirectorySuffix"
	 */
	private String outputDirectorySuffix;

	/**
	 * Skip build. Can be used in conjunction with build-helper-maven-plugin's uptodate-property
	 */
	private boolean skip;

	/**
	 * Output options. Used for example with type "js" to create protoc argument --js_out=[OPTIONS]:output_dir
	 * <p>
	 * Ignored when {@code <outputTargets>} is given
	 *
	 * @parameter property="outputOptions"
	 */
	private String outputOptions;

	/**
	 * This parameter lets you specify multiple protoc output targets.
	 * OutputTarget parameters: "type", "addSources", "cleanOutputFolder", "outputDirectory", "outputDirectorySuffix", "outputOptions", "pluginPath", "pluginArtifact".
	 * Type options: "java", "cpp", "python", "descriptor" (default: "java"); for proto3 also: "javanano", "csharp", "objc", "ruby", "js"
	 * 
	 * <pre>
	 * {@code
	 * <outputTargets>
	 * 	<outputTarget>
	 * 		<type>java</type>
	 * 		<addSources>none</addSources>
	 * 		<outputDirectory>src/main/java</outputDirectory>
	 * 	</outputTarget>
	 * 	<outputTarget>
	 * 		<type>grpc-java</type>
	 * 		<addSources>none</addSources>
	 * 		<outputDirectory>src/main/java</outputDirectory>
	 * 		<pluginArtifact>io.grpc:protoc-gen-grpc-java:1.0.1</pluginArtifact>
	 * 	</outputTarget>
	 * 	<outputTarget>
	 * 		<type>python</type>
	 * 		<addSources>none</addSources>
	 * 		<outputDirectory>src/main/python</outputDirectory>
	 * 	</outputTarget>
	 * 	<outputTarget>
	 * 		<type>dart</type>
	 * 		<addSources>none</addSources>
	 * 		<outputDirectory>lib/protobuf</outputDirectory>
	 * 		<pluginPath>protoc-gen-dart.exe</pluginPath>
	 * 	</outputTarget>
	 * <outputTargets>
	 * }
	 * </pre>
	 * 
	 * @parameter property="outputTargets"
	 */
	private OutputTarget[] outputTargets;

	/**
	 * Default extension for protobuf files
	 * 
	 * @parameter property="extension" default-value=".proto"
	 */
	private String extension;

	/**
	 * This parameter allows to use a protoc binary instead of the protoc-jar bundle
	 * 
	 * @parameter property="protocCommand"
	 */
	private String protocCommand;

	/**
	 * Maven artifact coordinates of the protoc binary to use
	 * Format: "groupId:artifactId:version" (eg, "com.google.protobuf:protoc:3.1.0")
	 *
	 * @parameter property="protocArtifact"
	 */
	private String protocArtifact;

	/**
	 * The Maven project.
	 * 
	 * @parameter property="project"
	 * @readonly
	 * @required
	 */
	private MavenProject project;

	/** 
	 * @parameter default-value="${localRepository}" 
	 * @readonly
	 * @required
	 */
	private ArtifactRepository localRepository;

	/** 
	 * @parameter default-value="${project.remoteArtifactRepositories}" 
	 * @readonly
	 * @required
	 */
	private List<ArtifactRepository> remoteRepositories;

	/** @component */
	private BuildContext buildContext;
	/** @component */
	private ArtifactFactory artifactFactory;
	/** @component */
	private ArtifactResolver artifactResolver;
	/** @component */
	protected MavenProjectHelper projectHelper;

	private File tempRoot = null;

	public void execute() throws MojoExecutionException {
		if (project.getPackaging() != null && "pom".equals(project.getPackaging().toLowerCase())) {
			getLog().info("Skipping 'pom' packaged project");
			return;
		}

		if(skip) {
			getLog().info("Skipping because of skip=true");
			return;
		}
		
		if (outputTargets == null || outputTargets.length == 0) {
			OutputTarget target = new OutputTarget();
			target.type = type;
			target.addSources = addSources;
			target.cleanOutputFolder = cleanOutputFolder;
			target.pluginPath = pluginPath;
			target.pluginArtifact = pluginArtifact;
			target.outputDirectory = outputDirectory;
			target.outputDirectorySuffix = outputDirectorySuffix;
			target.outputOptions = outputOptions;
			outputTargets = new OutputTarget[] {target};
		}

		boolean missingOutputDirectory = false;
		
		for (OutputTarget target : outputTargets) {
			target.addSources = target.addSources.toLowerCase().trim();
			if ("true".equals(target.addSources)) target.addSources = "main";
			
			if (target.outputDirectory == null) {
				String subdir = "generated-" + ("test".equals(target.addSources) ? "test-" : "") + "sources";
				target.outputDirectory = new File(project.getBuild().getDirectory() + File.separator + subdir + File.separator);
			}
			
			if (target.outputDirectorySuffix != null) {
				target.outputDirectory = new File(target.outputDirectory, target.outputDirectorySuffix);
			}
			
			String[] outputFiles = target.outputDirectory.list();
			if (outputFiles == null || outputFiles.length == 0) {
				missingOutputDirectory = true;
			}
		}
		
		if (!optimizeCodegen) {
			performProtoCompilation(true);
			return;
		}
		
		File successFile = new File(project.getBuild().getDirectory(), "pjmp-success.txt");
		try {
			long oldestOutputFileTime = minFileTime(outputTargets);
			long newestInputFileTime = maxFileTime(inputDirectories);
			if (successFile.exists() && newestInputFileTime < oldestOutputFileTime && !missingOutputDirectory) {
				getLog().info("Skipping code generation, proto files appear unchanged since last compilation");
				performProtoCompilation(false);
				return;
			}
			successFile.delete();
			performProtoCompilation(true);
			successFile.getParentFile().mkdirs();
			successFile.createNewFile();
		}
		catch (IOException e) {
			throw new MojoExecutionException("File operation failed: " + successFile, e);
		}
	}

	private void performProtoCompilation(boolean doCodegen) throws MojoExecutionException {
		if (doCodegen) prepareProtoc();
		
		// even if doCodegen == false, we still extract extra includes/inputs because addProtoSources might be requested
		// this could be optimized further
		File tmpDir = createTempDir("protocjar");
		
		// extract additional include types
		if (includeStdTypes || hasIncludeMavenTypes()) {
			try {
				File extraTypeDir = new File(tmpDir, "include");
				extraTypeDir.mkdir();
				getLog().info("Additional include types: " + extraTypeDir);
				addIncludeDir(extraTypeDir);
				if (includeStdTypes) Protoc.extractStdTypes(ProtocVersion.getVersion("-v"+protocVersion), tmpDir); // yes, tmpDir
				if (hasIncludeMavenTypes()) extractProtosFromDependencies(extraTypeDir, includeMavenTypes.equalsIgnoreCase("transitive"));
				deleteOnExitRecursive(extraTypeDir);
			}
			catch (IOException e) {
				throw new MojoExecutionException("Error extracting additional include types", e);
			}
		}
		
		if (inputDirectories == null || inputDirectories.length == 0) {
			File inputDir = new File(project.getBasedir().getAbsolutePath() + DEFAULT_INPUT_DIR);
			inputDirectories = new File[] { inputDir };
		}
		
		if (hasCompileMavenTypes()) {
			try {
				File mavenTypesCompileDir = new File(tmpDir, "mvncompile");
				mavenTypesCompileDir.mkdir();
				getLog().info("Files to compile from Maven dependencies (" + compileMavenTypes + "): " + mavenTypesCompileDir);
				addInputDir(mavenTypesCompileDir);
				extractProtosFromDependencies(mavenTypesCompileDir, compileMavenTypes.equalsIgnoreCase("transitive"));
				deleteOnExitRecursive(mavenTypesCompileDir);	
			}
			catch (IOException e) {
				throw new MojoExecutionException("Error extracting files from Maven dependencies", e);
			}
		}
		
		getLog().info("Input directories:");
		for (File input : inputDirectories) {
			getLog().info("    " + input);
			if ("all".equalsIgnoreCase(addProtoSources) || "inputs".equalsIgnoreCase(addProtoSources)) {
				List<String> incs = Arrays.asList("**/*" + extension);
				List<String> excs = new ArrayList<String>();
				projectHelper.addResource(project, input.getAbsolutePath(), incs, excs);			
			}
		}
		
		if (includeDirectories != null && includeDirectories.length > 0) {
			getLog().info("Include directories:");
			for (File include : includeDirectories) {
				getLog().info("    " + include);
				if ("all".equalsIgnoreCase(addProtoSources)) {
					List<String> incs = Arrays.asList("**/*" + extension);
					List<String> excs = new ArrayList<String>();
					projectHelper.addResource(project, include.getAbsolutePath(), incs, excs);
				}
			}
		}
		
		if (doCodegen) {
			getLog().info("Output targets:");
			for (OutputTarget target : outputTargets) getLog().info("    " + target);
			for (OutputTarget target : outputTargets) preprocessTarget(target);
			for (OutputTarget target : outputTargets) processTarget(target);
		}
		for (OutputTarget target : outputTargets) addGeneratedSources(target);
	}

	private void prepareProtoc() throws MojoExecutionException {
		if (protocCommand != null) {
			try {
				Protoc.runProtoc(protocCommand, new String[]{"--version"});
			}
			catch (Exception e) {
				protocCommand = null;
			}
		}
		
		if (protocCommand == null && protocArtifact == null) {
			if (isEmpty(protocVersion)) protocVersion = ProtocVersion.PROTOC_VERSION.mVersion;
			getLog().info("Protoc version: " + protocVersion);
			
			try {
				// option (1) - extract embedded protoc
				if (protocCommand == null && protocArtifact == null) {
					File protocFile = Protoc.extractProtoc(ProtocVersion.getVersion("-v"+protocVersion), false);
					protocCommand = protocFile.getAbsolutePath();
					try {
						// some linuxes don't allow exec in /tmp, try one dummy execution, switch to user home if it fails
						Protoc.runProtoc(protocCommand, new String[]{"--version"});
					}
					catch (Exception e) {
						tempRoot = new File(System.getProperty("user.home"));
						protocFile = Protoc.extractProtoc(ProtocVersion.getVersion("-v"+protocVersion), false, tempRoot);
						protocCommand = protocFile.getAbsolutePath();
					}
				}
			}
			catch (IOException e) {
				throw new MojoExecutionException("Error extracting protoc for version " + protocVersion, e);
			}
		}
		
		// option (2) - resolve protoc maven artifact (download)
		if (protocCommand == null && protocArtifact != null) {
			protocVersion = ProtocVersion.getVersion("-v:"+protocArtifact).mVersion;
			protocCommand = resolveArtifact(protocArtifact, null).getAbsolutePath();
			try {
				// some linuxes don't allow exec in /tmp, try one dummy execution, switch to user home if it fails
				Protoc.runProtoc(protocCommand, new String[]{"--version"});
			}
			catch (Exception e) {
				tempRoot = new File(System.getProperty("user.home"));
				protocCommand = resolveArtifact(protocArtifact, tempRoot).getAbsolutePath();
			}
		}
		getLog().info("Protoc command: " + protocCommand);
	}

	private void addIncludeDir(File dir) {
		includeDirectories = addDir(includeDirectories, dir);
	}
	private void addInputDir(File dir) {
		inputDirectories = addDir(inputDirectories, dir);
	}

	private boolean hasIncludeMavenTypes() {
		return includeMavenTypes.equalsIgnoreCase("direct") || includeMavenTypes.equalsIgnoreCase("transitive");
	}
	private boolean hasCompileMavenTypes() {
		return compileMavenTypes.equalsIgnoreCase("direct") || compileMavenTypes.equalsIgnoreCase("transitive");
	}

	private void extractProtosFromDependencies(File dir, boolean transitive) throws IOException {
		for (Artifact artifact : getArtifactsForProtoExtraction(transitive)) {
			if (artifact.getFile() == null) continue;
			getLog().debug("  Scanning artifact: " + artifact.getFile());
			InputStream is = null;
			try {
				if (artifact.getFile().isDirectory()) {
					for (File f : listFilesRecursively(artifact.getFile(), extension, new ArrayList<File>())) {
						is = new FileInputStream(f);
						String name = f.getAbsolutePath().replace(artifact.getFile().getAbsolutePath(), "");
						if (name.startsWith("/")) name = name.substring(1);
						writeProtoFile(dir, is, name);
						is.close();
					}
				}
				else {
					ZipInputStream zis = new ZipInputStream(new FileInputStream(artifact.getFile()));
					is = zis;
					ZipEntry ze;
					while ((ze = zis.getNextEntry()) != null) {
						if (ze.isDirectory() || !ze.getName().toLowerCase().endsWith(extension)) continue;
						writeProtoFile(dir, zis, ze.getName());
						zis.closeEntry();
					}					
				}
			}
			catch (IOException e) {
				getLog().info("  Error scanning artifact: " + artifact.getFile() + ": " + e);
			}
			finally {
				if (is != null) is.close();
			}			
		}
	}

	@SuppressWarnings("unchecked")
	private Set<Artifact> getArtifactsForProtoExtraction(boolean transitive) {
		if (transitive) return project.getArtifacts();
		return project.getDependencyArtifacts();
	}

	private List<File> listFilesRecursively(File directory, String ext, List<File> list) {
		for (File f : directory.listFiles()) {
			if (f.isFile() && f.canRead() && f.getName().toLowerCase().endsWith(ext)) list.add(f);
			else if (f.isDirectory() && f.canExecute()) listFilesRecursively(f, ext, list);
		}
		return list;
	}

	private void writeProtoFile(File dir, InputStream zis, String name) throws IOException {
		getLog().info("    " + name);
		File protoOut = new File(dir, name);
		protoOut.getParentFile().mkdirs();
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(protoOut);
			streamCopy(zis, fos);
		}
		finally {
			if (fos != null) fos.close();
		}
	}

	private void preprocessTarget(OutputTarget target) throws MojoExecutionException {
		if (!isEmpty(target.pluginArtifact)) {
			target.pluginPath = resolveArtifact(target.pluginArtifact, tempRoot).getAbsolutePath();
		}
		
		File f = target.outputDirectory;
		if (!f.exists()) {
			getLog().info(f + " does not exist. Creating...");
			f.mkdirs();
		}
		
		if (target.cleanOutputFolder) {
			try {
				getLog().info("Cleaning " + f);
				FileUtils.cleanDirectory(f);
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void processTarget(OutputTarget target) throws MojoExecutionException {
		boolean shaded = false;
		String targetType = target.type;
		if (targetType.equals("java-shaded") || targetType.equals("java_shaded")) {
			targetType = "java";
			shaded = true;
		}
		
		FileFilter fileFilter = new FileFilter(extension);
		for (File input : inputDirectories) {
			if (input == null) continue;
			
			if (input.exists() && input.isDirectory()) {
				Collection<File> protoFiles = FileUtils.listFiles(input, fileFilter, TrueFileFilter.INSTANCE);
				for (File protoFile : protoFiles) {
					if (target.cleanOutputFolder || buildContext.hasDelta(protoFile.getPath())) {
						processFile(protoFile, protocVersion, targetType, target.pluginPath, target.outputDirectory, target.outputOptions);
					}
					else {
						getLog().info("Not changed " + protoFile);
					}
				}
			}
			else {
				if (input.exists()) getLog().warn(input + " is not a directory");
				else getLog().warn(input + " does not exist");
			}
		}
		
		if (shaded) {
			try {
				getLog().info("    Shading (version " + protocVersion + "): " + target.outputDirectory);
				Protoc.doShading(target.outputDirectory, protocVersion);
			}
			catch (IOException e) {
				throw new MojoExecutionException("Error occurred during shading", e);
			}
		}		
	}

	private void addGeneratedSources(OutputTarget target) throws MojoExecutionException {
		boolean mainAddSources = "main".endsWith(target.addSources);
		boolean testAddSources = "test".endsWith(target.addSources);
		
		if (mainAddSources) {
			getLog().info("Adding generated sources (" + target.type + "): " + target.outputDirectory);
			project.addCompileSourceRoot(target.outputDirectory.getAbsolutePath());
		}
		if (testAddSources) {
			getLog().info("Adding generated test sources (" + target.type + "): " + target.outputDirectory);
			project.addTestCompileSourceRoot(target.outputDirectory.getAbsolutePath());
		}
		if (mainAddSources || testAddSources) {
			buildContext.refresh(target.outputDirectory);
		}
	}

	private void processFile(File file, String version, String type, String pluginPath, File outputDir, String outputOptions) throws MojoExecutionException {
		getLog().info("    Processing ("+ type + "): " + file.getName());

		try {
			buildContext.removeMessages(file);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ByteArrayOutputStream err = new ByteArrayOutputStream();
			TeeOutputStream outTee = new TeeOutputStream(System.out, out);
			TeeOutputStream errTee = new TeeOutputStream(System.err, err);
			
			int ret = 0;
			Collection<String> cmd = buildCommand(file, version, type, pluginPath, outputDir, outputOptions);
			if (protocCommand == null) ret = Protoc.runProtoc(cmd.toArray(new String[0]), outTee, errTee);
			else ret = Protoc.runProtoc(protocCommand, Arrays.asList(cmd.toArray(new String[0])), outTee, errTee);
			
			// add eclipse m2e warnings/errors
			String errStr = err.toString();
			if (!isEmpty(errStr)) {
				int severity = (ret != 0) ? BuildContext.SEVERITY_ERROR : BuildContext.SEVERITY_WARNING;
				String[] lines = errStr.split("\\n", -1);
				for (String line : lines) {
					int lineNum = 0;
					int colNum = 0;
					String msg = line;
					if (line.contains(file.getName())) {
						String[] parts = line.split(":", 4);
						if (parts.length == 4) {
							try {
								lineNum = Integer.parseInt(parts[1]);
								colNum = Integer.parseInt(parts[2]);
								msg = parts[3];
							}
							catch (Exception e) {
								getLog().warn("Failed to parse protoc warning/error for " + file);
							}
						}
					}
					buildContext.addMessage(file, lineNum, colNum, msg, severity, null);
				}
			}
			
			if (ret != 0) throw new MojoExecutionException("protoc-jar failed for " + file + ". Exit code " + ret);
		}
		catch (InterruptedException e) {
			throw new MojoExecutionException("Interrupted", e);
		}
		catch (IOException e) {
			throw new MojoExecutionException("Unable to execute protoc-jar for " + file, e);
		}
	}

	private Collection<String> buildCommand(File file, String version, String type, String pluginPath, File outputDir, String outputOptions) throws MojoExecutionException {
		Collection<String> cmd = new ArrayList<String>();
		populateIncludes(cmd);
		cmd.add("-I" + file.getParentFile().getAbsolutePath());
		if ("descriptor".equals(type)) {
			File outFile = new File(outputDir, file.getName());
			cmd.add("--descriptor_set_out=" + FilenameUtils.removeExtension(outFile.toString()) + ".desc");
			if (includeImports) {
				cmd.add("--include_imports");
			}
			if (outputOptions != null) {
				for (String arg : outputOptions.split("\\s+")) cmd.add(arg);
			}
		}
		else {
			if (outputOptions != null) {
				cmd.add("--" + type + "_out=" + outputOptions + ":" + outputDir);
			}
			else {
				cmd.add("--" + type + "_out=" + outputDir);
			}

			if (pluginPath != null) {
				getLog().info("    Plugin path: " + pluginPath);
				cmd.add("--plugin=protoc-gen-" + type + "=" + pluginPath);
			}
		}
		cmd.add(file.toString());
		if (version != null) cmd.add("-v" + version);
		return cmd;
	}

	private void populateIncludes(Collection<String> args) throws MojoExecutionException {
		for (File include : includeDirectories) {
			if (!include.exists()) throw new MojoExecutionException("Include path '" + include.getPath() + "' does not exist");
			if (!include.isDirectory()) throw new MojoExecutionException("Include path '" + include.getPath() + "' is not a directory");
			args.add("-I" + include.getPath());
		}
	}

	private File resolveArtifact(String artifactSpec, File dir) throws MojoExecutionException {
		try {
			Properties detectorProps = new Properties();
			new PlatformDetector().detect(detectorProps, null);
			String platform = detectorProps.getProperty("os.detected.classifier");
			
			getLog().info("Resolving artifact: " + artifactSpec + ", platform: " + platform);
			String[] as = parseArtifactSpec(artifactSpec, platform);
			Artifact artifact = artifactFactory.createDependencyArtifact(as[0], as[1], VersionRange.createFromVersionSpec(as[2]), as[3], as[4], Artifact.SCOPE_RUNTIME);
			artifactResolver.resolve(artifact, remoteRepositories, localRepository);
			
			File tempFile = File.createTempFile(as[1], "."+as[3], dir);
			copyFile(artifact.getFile(), tempFile);
			tempFile.setExecutable(true);
			tempFile.deleteOnExit();
			return tempFile;
		}
		catch (Exception e) {
			throw new MojoExecutionException("Error resolving artifact: " + artifactSpec, e);
		}
	}
	static String[] parseArtifactSpec(String artifactSpec, String platform) {
		String[] as = artifactSpec.split(":");
		String[] ret = Arrays.copyOf(as, 5);		
		if (ret[3] == null) ret[3] = "exe";
		if (ret[4] == null) ret[4] = platform;
		return ret;
    }

	static long minFileTime(OutputTarget[] outputTargets) {
		long minTime = Long.MAX_VALUE;
		for (OutputTarget target : outputTargets) minTime = Math.min(minTime, minFileTime(target.outputDirectory));
		return minTime;
	}
	static long maxFileTime(File[] dirs) {
		long maxTime = Long.MIN_VALUE;
		for (File dir : dirs) maxTime = Math.max(maxTime, maxFileTime(dir));
		return maxTime;
	}

	static long minFileTime(File current) {
		if (!current.isDirectory()) return current.lastModified();
		long minTime = Long.MAX_VALUE;
		for (File entry: current.listFiles()) minTime = Math.min(minTime, minFileTime(entry));
		return minTime;
	}
	static long maxFileTime(File current) {
		if (!current.isDirectory()) return current.lastModified();
		long maxTime = Long.MIN_VALUE;
		for (File entry: current.listFiles()) maxTime = Math.max(maxTime, maxFileTime(entry));
		return maxTime;
	}

	static File createTempDir(String name) throws MojoExecutionException {
		try {
			File tmpDir = File.createTempFile(name, "");
			tmpDir.delete();
			tmpDir.mkdirs();
			tmpDir.deleteOnExit();
			return tmpDir;
		}
		catch (IOException e) {
			throw new MojoExecutionException("Error creating temporary directory: " + name, e);
		}
	}

	static File[] addDir(File[] dirs, File dir) {
		if (dirs == null) {
			dirs = new File[] {dir};
		}
		else {
			dirs = Arrays.copyOf(dirs, dirs.length + 1);
			dirs[dirs.length - 1] = dir;
		}
		return dirs;
	}

	static void deleteOnExitRecursive(File dir) {
		dir.deleteOnExit();
		for (File f : dir.listFiles()) {
			f.deleteOnExit();
			if (f.isDirectory()) deleteOnExitRecursive(f);
		}
	}

	static File copyFile(File srcFile, File destFile) throws IOException {		
		FileInputStream is = null;
		FileOutputStream os = null;
		try {
			is = new FileInputStream(srcFile);
			os = new FileOutputStream(destFile);
			streamCopy(is, os);
		}
		finally {
			if (is != null) is.close();
			if (os != null) os.close();
		}
		return destFile;
	}

	static void streamCopy(InputStream in, OutputStream out) throws IOException {
		int read = 0;
		byte[] buf = new byte[4096];
		while ((read = in.read(buf)) > 0) out.write(buf, 0, read);		
	}

	static boolean isEmpty(String s) {
		if (s != null && s.length() > 0) return false;
		return true;
	}

	static class FileFilter implements IOFileFilter
	{
		String extension;

		public FileFilter(String extension) {
			this.extension = extension;
		}

		public boolean accept(File dir, String name) {
			return name.endsWith(extension);
		}

		public boolean accept(File file) {
			return file.getName().endsWith(extension);
		}
	}
}
