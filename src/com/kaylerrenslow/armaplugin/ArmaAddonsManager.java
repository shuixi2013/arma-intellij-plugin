package com.kaylerrenslow.armaplugin;

import com.intellij.openapi.project.Project;
import com.kaylerrenslow.armaDialogCreator.arma.header.HeaderFile;
import com.kaylerrenslow.armaDialogCreator.arma.header.HeaderParseException;
import com.kaylerrenslow.armaDialogCreator.arma.header.HeaderParseResult;
import com.kaylerrenslow.armaDialogCreator.arma.header.HeaderParser;
import com.kaylerrenslow.armaDialogCreator.util.XmlUtil;
import com.kaylerrenslow.armaplugin.ArmaAddonsIndexingCallback.Step;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Kayler
 * @since 09/22/2017
 */
public class ArmaAddonsManager {
	private ArmaAddonsManager() {
	}

	private final List<ArmaAddon> addons = new ArrayList<>();
	private final List<ArmaAddon> addonsReadOnly = Collections.unmodifiableList(addons);

	/**
	 * @return a read-only list containing all addons
	 */
	@NotNull
	public static List<ArmaAddon> getAddons() {
		synchronized (instance) {
			return instance.addonsReadOnly;
		}
	}

	/**
	 * Will load addons into {@link #getAddons()}.
	 *
	 * @param config   the config to use
	 * @param callback
	 */
	//todo: document this method
	//todo: we should check to see if we need to re-extract each addon. If we don't need to extract it, load it from reference directory
	public static void loadAddonsAsync(@NotNull ArmaAddonsProjectConfig config, @Nullable File logFile, @NotNull ArmaAddonsIndexingCallback callback) {
		instance._loadAddonsAsync(config, logFile, callback);
	}


	private void _loadAddonsAsync(@NotNull ArmaAddonsProjectConfig config, @Nullable File logFile, @NotNull ArmaAddonsIndexingCallback callback) {
		Thread t = new Thread(() -> {
			List<ArmaAddon> armaAddons;
			ForwardingThread forwardingThread = new ForwardingThread(callback, logFile);
			forwardingThread.start();
			forwardingThread.log("[BEGIN LOAD ADDONS]\n");
			try {
				armaAddons = doLoadAddons(config, forwardingThread);
			} catch (Exception e) {
				e.printStackTrace();
				forwardingThread.logError("Couldn't complete indexing addons", e);
				return;
			} finally {

				forwardingThread.finishedIndex();

				if (forwardingThread.getRootTempDirectory() != null) {
					//this is in case the the try-catch block resulted in exception and cleanup couldn't be performed normally
					boolean deleted = deleteDirectory(forwardingThread.getRootTempDirectory());
					if (!deleted) {
						forwardingThread.errorMessage(
								String.format(
										getBundle().getString("failed-to-delete-root-temp-directory-f"),
										forwardingThread.getRootTempDirectory().getAbsolutePath()
								), null
						);
					}
				}

				forwardingThread.log("[EXIT LOAD ADDONS]\n\n");
				forwardingThread.closeThread();
			}
			synchronized (instance) {
				this.addons.clear();
				this.addons.addAll(armaAddons);
			}
		}, "ArmaAddonsManager - Load Addons");
		t.start();
	}

	@NotNull
	private List<ArmaAddon> doLoadAddons(@NotNull ArmaAddonsProjectConfig config,
										 @NotNull ForwardingThread forwardingThread) throws Exception {
		ResourceBundle bundle = getBundle();

		File refDir = new File(config.getAddonsReferenceDirectory());
		if (refDir.exists() && !refDir.isDirectory()) {
			throw new IllegalArgumentException("reference directory isn't a directory");
		}
		if (!refDir.exists()) {
			boolean mkdirs = refDir.mkdirs();
			if (!mkdirs) {
				throw new IllegalStateException("couldn't make directories for the reference directory");
			}
		}
		File armaTools = ArmaPluginUserData.getInstance().getArmaToolsDirectory();
		if (armaTools == null) {
			throw new IllegalStateException("arma tools directory isn't set");
		}

		List<ArmaAddonHelper> addonHelpers = new ArrayList<>();

		{//Collect all @ prefixed folders that aren't blacklisted and is whitelisted (ignore whitelist when it's empty).
			List<File> addonRoots = new ArrayList<>(config.getAddonsRoots().size());
			for (String addonRootPath : config.getAddonsRoots()) {
				File f = new File(addonRootPath);
				if (!f.exists()) {
					continue;
				}
				if (!f.isDirectory()) {
					continue;
				}
				forwardingThread.log("Found addon root " + f.getAbsolutePath());
				addonRoots.add(f);
			}

			boolean useWhitelist = !config.getWhitelistedAddons().isEmpty();

			for (File addonRoot : addonRoots) {
				File[] files = addonRoot.listFiles((dir, name) -> {
					if (name.length() == 0) {
						return false;
					}
					if (name.charAt(0) != '@') {
						return false;
					}
					if (config.getBlacklistedAddons().contains(name)) {
						forwardingThread.log("Addon excluded (blacklisted):" + name);
						return false;
					}

					return !useWhitelist || config.getWhitelistedAddons().contains(name);
				});
				if (files == null) {
					continue;
				}
				for (File addonDir : files) {
					addonHelpers.add(new ArmaAddonHelper(addonDir));
					forwardingThread.log("Addon directory marked for indexing: " + addonDir.getAbsolutePath());
				}
			}
		}

		{ //tell forwarding thread that the index is about to begin
			List<String> addonsMarkedForIndex = addonHelpers.stream().map(helper -> {
				return helper.getAddonDirName();
			}).collect(Collectors.toList());

			forwardingThread.startedIndex(new ArmaAddonsIndexingData(config, addonsMarkedForIndex));
		}

		File tempDir;
		{// Create a temp folder to extract the pbo in.
			String tempDirName = "_armaPluginTemp";

			// Make sure directory doesn't exist to ensure we aren't overwriting/deleting existing data
			while (true) {
				boolean matched = false;
				File[] files = refDir.listFiles();
				if (files == null) {
					throw new IllegalStateException("files is null despite refDir being a directory");
				}
				for (File f : files) {
					if (f.getName().equals(tempDirName)) {
						tempDirName = tempDirName + "_";
						matched = true;
						break;
					}
				}
				if (!matched) {
					break;
				}
			}
			tempDir = new File(refDir.getAbsolutePath() + "/" + tempDirName);
			boolean mkdirs = tempDir.mkdirs();
			if (!mkdirs) {
				throw new IllegalStateException("couldn't make the temp directory for extracting");
			}
			forwardingThread.setRootTempDirectory(tempDir);
			forwardingThread.log("Temp directory for addons extraction:" + tempDir.getAbsolutePath());
		}

		for (ArmaAddonHelper helper : addonHelpers) {
			if (helper.isCancelled()) {
				continue;
			}

			//reason for passing extractDirs instead of placing it in doAllWorkForAddonHelper
			//is because the addon could be cancelled half way through pbo extraction and we want to make sure
			//the data is cleaned up
			final List<File> extractDirs = Collections.synchronizedList(new ArrayList<>());

			forwardingThread.indexStartedForAddon(helper);
			forwardingThread.log("INDEX STARTED for addon " + helper.getAddonDirName());

			boolean loadedFromFile = loadAddonFromReferenceDirectory(helper, forwardingThread, refDir);
			if (!loadedFromFile) {
				doAllWorkForAddonHelper(helper, refDir, armaTools, tempDir, forwardingThread, extractDirs);
			}


			//delete extract directories to free up disk space for next addon extraction
			forwardingThread.stepStart(helper, Step.Cleanup);
			for (File extractDir : extractDirs) {
				boolean success = deleteDirectory(extractDir);
				if (success) {
					forwardingThread.log(
							String.format(bundle.getString("deleted-temp-directory-f"), extractDir)
					);
				} else {
					forwardingThread.warningMessage(
							helper,
							String.format(bundle.getString("failed-to-delete-temp-directory-f"), extractDir),
							null
					);
				}
			}
			forwardingThread.stepFinish(helper, Step.Cleanup);
			forwardingThread.indexFinishedForAddon(helper);
			forwardingThread.log("INDEX FINISHED for addon " + helper.getAddonDirName());
		}

		boolean success = deleteDirectory(tempDir);
		if (success) {
			forwardingThread.log(
					String.format(bundle.getString("deleted-temp-directory-f"), tempDir)
			);
		} else {
			forwardingThread.logWarning(
					String.format(bundle.getString("failed-to-delete-temp-directory-f"), tempDir),
					null
			);
		}

		List<ArmaAddon> addons = new ArrayList<>(addonHelpers.size());
		for (ArmaAddonHelper helper : addonHelpers) {
			if (helper.isCancelled()) {
				forwardingThread.log("Addon cancelled: " + helper.getAddonDirName());
				continue;
			}
			forwardingThread.log("Addon finished: " + helper.getAddonDirName());
			addons.add(new ArmaAddonImpl(helper));
		}

		return addons;
	}

	/**
	 * Loads a {@link ArmaAddon} instance from existing files in the reference directory.
	 *
	 * @param helper the addon helper to retrieve existing files for
	 * @param refDir the reference directory
	 * @return if the the {@link ArmaAddonHelper} instance was loaded from the reference directory, or false if
	 * it couldn't be loaded due to it being out of date or there is nothing to load for the helper in the reference directory
	 */
	private boolean loadAddonFromReferenceDirectory(@NotNull ArmaAddonHelper helper,
													@NotNull ForwardingThread forwardingThread,
													@NotNull File refDir) {
		File[] files = refDir.listFiles((dir, name) -> {
			return name.equals(helper.getAddonName());
		});
		if (files == null || files.length == 0) {
			return false;
		}
		File addonDirectoryInRefDir = files[0];
		if (!addonDirectoryInRefDir.isDirectory()) {
			return false;
		}
		{ //check if the addon's cache in the reference directory is valid or not
			files = addonDirectoryInRefDir.listFiles((dir, name) -> name.equals(".cacheproperties"));
			if (files != null && files.length > 0) {
				try {
					Properties cacheProperties = new Properties();
					cacheProperties.load(new InputStreamReader(new FileInputStream(files[0]), StandardCharsets.UTF_8));
					String valid = cacheProperties.getProperty("valid");
					if (valid.equals("false")) {
						return false;
					}
				} catch (IOException ignore) {

				}
			}
		}

		//get all config.cpp files
		final String CONFIG_NAME = "config.cpp";
		List<File> configFiles = new ArrayList<>();
		{//locate all config.bin files
			LinkedList<File> toVisit = new LinkedList<>();
			toVisit.add(addonDirectoryInRefDir);
			while (!toVisit.isEmpty()) {
				File visit = toVisit.removeFirst();
				File[] children = visit.listFiles();
				if (children != null) {
					Collections.addAll(toVisit, children);
				}
				if (visit.getName().equalsIgnoreCase(CONFIG_NAME)) {
					configFiles.add(visit);
				}
			}
		}

		parseConfigsForHelper(helper, forwardingThread, configFiles);
		return true;
	}

	private void doAllWorkForAddonHelper(@NotNull ArmaAddonHelper helper,
										 @NotNull File refDir, @NotNull File armaTools, @NotNull File tempDir,
										 @NotNull ForwardingThread forwardingThread,
										 @NotNull List<File> extractDirs) throws Exception {
		ResourceBundle bundle = getBundle();

		final List<File> debinarizedConfigs = Collections.synchronizedList(new ArrayList<>());

		//extract pbo's into temp directory
		if (extractPBOsForHelper(helper, armaTools, tempDir, forwardingThread, extractDirs)) {
			return;
		}

		//de-binarize the configs and locate all sqf files
		if (debinarizeConfigsForHelper(helper, armaTools, forwardingThread, extractDirs, debinarizedConfigs)) {
			return;
		}

		//parse the configs
		if (parseConfigsForHelper(helper, forwardingThread, debinarizedConfigs)) {
			return;
		}


		{//copy the files out of temp directory that we want to keep
			//create folder in reference directory
			File destDir = new File(refDir.getAbsolutePath() + "/" + helper.getAddonDirName());
			boolean mkdirs = destDir.mkdirs();
			if (!mkdirs) {
				forwardingThread.errorMessage(
						helper,
						String.format(
								bundle.getString("failed-to-create-reference-directory-f"),
								destDir.getAbsolutePath(),
								helper.getAddonDirName()
						), null
				);
				return;
			}
			helper.setAddonDirectoryInReferenceDirectory(destDir);

			forwardingThread.stepStart(helper, Step.SaveReferences);

			//copy over sqf and header files into destDir and replicate folder structure in destDir
			LinkedList<File> toVisit = new LinkedList<>();
			LinkedList<File> traverseCopy = new LinkedList<>();
			for (File extractDir : extractDirs) {
				toVisit.add(extractDir);
				File folderCopy = new File(destDir.getAbsolutePath() + "/" + extractDir.getName());
				traverseCopy.add(folderCopy);
				boolean mkdirs1 = folderCopy.mkdirs();
				if (!mkdirs1) {
					forwardingThread.errorMessage(
							helper,
							String.format(
									bundle.getString("failed-to-create-reference-directory-f"),
									folderCopy.getAbsolutePath(),
									helper.getAddonDirName()
							), null
					);
				}
			}
			while (!toVisit.isEmpty()) {
				File visit = toVisit.pop();
				File folderCopy = traverseCopy.pop();

				File[] children = visit.listFiles();
				if (children != null) {
					for (File child : children) {
						if (helper.isCancelled()) {
							return;
						}
						if (child.isFile() && child.getName().endsWith(".sqf")
								|| child.getName().endsWith(".cpp")
								|| child.getName().endsWith(".h")
								|| child.getName().endsWith(".hh")
								|| child.getName().endsWith(".hpp")) {
							File target = new File(folderCopy.getAbsolutePath() + "/" + child.getName());
							try {
								Files.copy(
										child.toPath(),
										target.toPath(),
										StandardCopyOption.REPLACE_EXISTING
								);
								forwardingThread.message(
										helper,
										String.format(
												bundle.getString("copied-file-to-f"),
												child.getAbsolutePath(),
												target.getAbsolutePath()
										)
								);
							} catch (IOException e) {
								forwardingThread.errorMessage(
										helper,
										String.format(
												bundle.getString("couldnt-copy-file-to-f"),
												child.getAbsolutePath(),
												target.getAbsolutePath()
										), e
								);
								continue;
							}
						} else if (child.isDirectory()) {
							File newFolder = new File(folderCopy.getAbsolutePath() + "/" + visit.getName());
							if (!newFolder.exists()) {
								boolean mkdirs1 = newFolder.mkdirs();
								if (!mkdirs1) {
									forwardingThread.errorMessage(
											helper,
											String.format(
													bundle.getString("failed-to-create-directory-f"),
													newFolder.getAbsolutePath()
											), null
									);
									continue;
								}
							}
							//keep toVisit.push() down here in case the new folder can't be created
							toVisit.push(child);
							traverseCopy.push(newFolder);
						}
					}
				}
			}
			forwardingThread.stepFinish(helper, Step.SaveReferences);
		}
	}

	/**
	 * Detects all .pbo files in an addon directory and extracts them concurrently.
	 *
	 * @param helper           the helper to extract PBO's for
	 * @param armaTools        Arma Tools directory
	 * @param tempDir          temporary directory to extract PBO contents to
	 * @param forwardingThread instance to use
	 * @param extractDirs      thread safe list to add File instances to that were used to extract PBO's in.
	 *                         These files are located in the tempDir
	 * @return true if the extraction was successful, false if there was an error or extraction was cancelled
	 */
	private boolean extractPBOsForHelper(@NotNull ArmaAddonHelper helper, @NotNull File armaTools, @NotNull File tempDir,
										 @NotNull ForwardingThread forwardingThread, @NotNull List<File> extractDirs) throws InterruptedException {
		ResourceBundle bundle = getBundle();
		File addonsDir = null;
		{ //locate the "addons" folder, which contains all the pbo files
			File[] addonDirFiles = helper.getAddonDirectory().listFiles();
			if (addonDirFiles == null) {
				throw new IllegalStateException("addon directory isn't a directory: " + helper.getAddonDirectory());
			}
			for (File addonDirFile : addonDirFiles) {
				if (!addonDirFile.getName().equalsIgnoreCase("addons")) {
					continue;
				}
				addonsDir = addonDirFile;
				break;
			}
			if (addonsDir == null) {
				forwardingThread.warningMessage(helper,
						String.format(
								bundle.getString("couldnt-find-addons-dir-f"),
								helper.getAddonDirectory().getAbsolutePath()
						), null
				);
				return false;
			}
		}
		File[] pboFiles = addonsDir.listFiles((dir, name) -> name.endsWith(".pbo"));
		if (pboFiles == null) {
			forwardingThread.warningMessage(helper,
					String.format(
							bundle.getString("no-pbos-were-in-addons-dir-f"),
							helper.getAddonDirectory().getAbsolutePath()
					), null
			);
			return false;
		}

		{ //print to log all pbo files marked for extraction
			StringBuilder sb = new StringBuilder();
			sb.append("All PBO's marked to extract:[\n");
			for (File pboFile : pboFiles) {
				sb.append('\t');
				sb.append(pboFile.getAbsolutePath());
				sb.append('\n');
			}
			sb.append(']');
			forwardingThread.log(sb.toString());
		}

		//make it so each thread doesn't do more work than the other. Also, partition the data.
		Arrays.sort(pboFiles, Comparator.comparingLong(File::length));
		List<File> left = new ArrayList<>(pboFiles.length);
		List<File> right = new ArrayList<>(pboFiles.length);
		int leftCapacity = 0;
		int rightCapacity = 0;
		for (int i = pboFiles.length - 1; i >= 0; i--) {//iterate backwards since array is sorted A-Z
			File file = pboFiles[i];
			if (leftCapacity < rightCapacity) {
				left.add(file);
				leftCapacity += file.length();
			} else {
				right.add(file);
				rightCapacity += file.length();
			}
		}


		forwardingThread.stepStart(helper, Step.ExtractPBOs);

		Function<List<File>, Void> extractPbos = pboFilesToExtract -> {
			StringBuilder sb = new StringBuilder();
			sb.append("Extracting PBO's on thread ").append(Thread.currentThread().getName()).append(": [\n");
			for (File pboFile : pboFilesToExtract) {
				sb.append('\t');
				sb.append(pboFile.getName());
				sb.append('\n');
			}
			sb.append(']');
			forwardingThread.log(sb.toString());

			for (File pboFile : pboFilesToExtract) {
				if (helper.isCancelled()) {
					return null;
				}
				File extractDir = new File(
						tempDir.getAbsolutePath() + "/" + helper.getAddonDirName() +
								"/" +
								pboFile.getName().substring(0, pboFile.getName().length() - ".pbo".length())
				);
				boolean mkdirs = extractDir.mkdirs();
				if (!mkdirs) {
					forwardingThread.errorMessage(
							helper, String.format(
									bundle.getString("failed-to-create-temp-directory-f"),
									extractDir.getAbsolutePath(),
									helper.getAddonDirName()
							), null
					);
					return null;
				}

				extractDirs.add(extractDir);
				forwardingThread.log("Created extract directory: " + extractDir.getAbsolutePath());
				boolean success = false;
				Exception e = null;
				try {
					success = ArmaTools.extractPBO(
							armaTools,
							pboFile,
							extractDir, 10 * 60 * 1000 /*10 minutes before suspend*/,
							null, null
					);
					forwardingThread.message(helper,
							String.format(
									bundle.getString("extracted-pbo-f"),
									pboFile.getName(),
									helper.getAddonDirName()
							)
					);
				} catch (IOException e1) {
					e = e1;
				}
				if (!success) {
					forwardingThread.errorMessage(helper,
							String.format(
									bundle.getString("couldnt-extract-pbo-f"),
									pboFile.getName(), helper.getAddonDirName()
							), e
					);
				}
			}
			return null;
		};
		ArmaToolsWorkerThread t1 = new ArmaToolsWorkerThread(() -> {
			extractPbos.apply(left);
		}, 1
		);
		ArmaToolsWorkerThread t2 = new ArmaToolsWorkerThread(() -> {
			extractPbos.apply(right);
		}, 2
		);
		t1.start();
		t2.start();
		t1.join();
		t2.join();
		if (helper.isCancelled()) {
			return false;
		}
		forwardingThread.message(helper,
				String.format(
						bundle.getString("extracted-all-pbo-f"),
						helper.getAddonDirName()
				)
		);
		forwardingThread.stepFinish(helper, Step.ExtractPBOs);
		return true;
	}


	/**
	 * Detects all config.bin files in the provided extractDirs list. After all config.bin files have been located,
	 * they are converted to config.cpp files with Arma Tools concurrently.
	 * <p>
	 * If a config.cpp file is also found while looking for config.bin files, it will automatically be added to
	 * <code>debinarizedConfigs</code>.
	 *
	 * @param helper             the helper to convert config.bin files for
	 * @param armaTools          the Arma Tools directory
	 * @param forwardingThread   instance to use
	 * @param extractDirs        list that should only be relevant to the provided {@link ArmaAddonHelper} instance and contains
	 *                           all directories that may contain a config.bin file
	 * @param debinarizedConfigs a thread safe list that all config.cpp files should be added to
	 * @return true if all configs were debinarized successfully, or false if an error occurred
	 * or the helper was cancelled
	 */
	private boolean debinarizeConfigsForHelper(@NotNull ArmaAddonHelper helper, @NotNull File armaTools,
											   @NotNull ForwardingThread forwardingThread, @NotNull List<File> extractDirs,
											   @NotNull List<File> debinarizedConfigs) {
		ResourceBundle bundle = getBundle();

		final String BINARIZED_CONFIG_NAME = "config.bin";
		List<File> configBinFiles = new ArrayList<>();
		{//locate all config.bin files
			LinkedList<File> toVisit = new LinkedList<>();
			toVisit.addAll(extractDirs);
			while (!toVisit.isEmpty()) {
				File visit = toVisit.removeFirst();
				File[] children = visit.listFiles();
				if (children != null) {
					Collections.addAll(toVisit, children);
				}
				if (visit.getName().equalsIgnoreCase(BINARIZED_CONFIG_NAME)) {
					configBinFiles.add(visit);
				} else if (visit.getName().equalsIgnoreCase("config.cpp")) {
					forwardingThread.log("Found pre-debinarized config for addon '"
							+ helper.getAddonDirName() + "'. Added to parsed configs:"
							+ visit.getAbsolutePath()
					);
					debinarizedConfigs.add(visit);
				}
			}
		}

		{ //print to log all config.bin files marked for debinarize
			StringBuilder sb = new StringBuilder();
			sb.append("All config.bin marked to debinarize:[\n");
			for (File configBinFile : configBinFiles) {
				sb.append('\t');
				sb.append(configBinFile.getAbsolutePath());
				sb.append('\n');
			}
			sb.append(']');
			forwardingThread.log(sb.toString());
		}

		//make it so each thread doesn't do more work than the other. Also, partition the data.
		configBinFiles.sort(Comparator.comparingLong(File::length));
		List<File> left = new ArrayList<>(configBinFiles.size());
		List<File> right = new ArrayList<>(configBinFiles.size());
		int leftCapacity = 0;
		int rightCapacity = 0;
		for (int i = configBinFiles.size() - 1; i >= 0; i--) {//iterate backwards since array is sorted A-Z
			File file = configBinFiles.get(i);
			if (leftCapacity < rightCapacity) {
				left.add(file);
				leftCapacity += file.length();
			} else {
				right.add(file);
				rightCapacity += file.length();
			}
		}

		forwardingThread.stepStart(helper, Step.DeBinarizeConfigs);

		Function<List<File>, Void> convertConfigBinFiles = configBinFilesToConvert -> {
			StringBuilder sb = new StringBuilder();
			sb.append("DeBinarizing config.bin files on thread ").append(Thread.currentThread().getName()).append(": [\n");
			for (File configBinFile : configBinFilesToConvert) {
				sb.append('\t');
				sb.append(configBinFile.getAbsolutePath());
				sb.append('\n');
			}
			sb.append(']');
			forwardingThread.log(sb.toString());

			for (File configBinFile : configBinFilesToConvert) {
				if (helper.isCancelled()) {
					return null;
				}
				String newPath = configBinFile.getParentFile().getAbsolutePath() + "/config.cpp";
				File debinarizedFile = new File(newPath);
				Exception e = null;
				boolean success = false;
				try {
					success = ArmaTools.convertBinConfigToText(
							armaTools,
							configBinFile,
							debinarizedFile,
							10 * 1000 /*10 seconds*/, null, null
					);
				} catch (IOException e1) {
					e = e1;
				}
				if (!success) {
					forwardingThread.errorMessage(helper,
							String.format(
									bundle.getString("couldnt-debinarize-config-f"),
									configBinFile.getAbsolutePath(), helper.getAddonDirName()
							), e
					);
					continue;
				}
				debinarizedConfigs.add(debinarizedFile);
				forwardingThread.message(helper,
						String.format(
								bundle.getString("debinarized-config-f"),
								helper.getAddonDirName(), configBinFile.getAbsolutePath()
						)
				);
			}
			return null;
		};
		ArmaToolsWorkerThread t1 = new ArmaToolsWorkerThread(() -> {
			convertConfigBinFiles.apply(left);
		}, 1
		);
		ArmaToolsWorkerThread t2 = new ArmaToolsWorkerThread(() -> {
			convertConfigBinFiles.apply(right);
		}, 2
		);
		t1.start();
		t2.start();
		try {
			t1.join();
		} catch (InterruptedException ignore) {

		}
		try {
			t2.join();
		} catch (InterruptedException ignore) {

		}
		if (helper.isCancelled()) {
			return false;
		}
		forwardingThread.message(
				helper,
				String.format(
						bundle.getString("debinarized-all-config-f"),
						helper.getAddonDirName()
				)
		);
		forwardingThread.stepFinish(helper, Step.DeBinarizeConfigs);
		return true;
	}

	/**
	 * Parses all de-binarized configs and stores them in the provided {@link ArmaAddonHelper} instance.
	 * The parsing uses Arma Dialog Creator's PreProcessor and Parser.
	 *
	 * @param helper             helper to use
	 * @param forwardingThread   instance to use
	 * @param debinarizedConfigs list of de-binarized configs to preprocess and parse
	 * @return true if the parse was completed successfully, false if there was an error or the parsing was cancelled
	 */
	private boolean parseConfigsForHelper(@NotNull ArmaAddonHelper helper, @NotNull ForwardingThread forwardingThread,
										  @NotNull List<File> debinarizedConfigs) {
		ResourceBundle bundle = getBundle();

		forwardingThread.stepStart(helper, Step.ParseConfigs);
		for (File configFile : debinarizedConfigs) {
			if (helper.isCancelled()) {
				return false;
			}
			try {
				HeaderParseResult parseResult = HeaderParser.parse(configFile, configFile.getParentFile());
				helper.getParseResults().add(parseResult);
				forwardingThread.message(helper,
						String.format(
								bundle.getString("parsed-config-f"),
								configFile.getAbsolutePath()
						)
				);
			} catch (HeaderParseException | IOException e) {
				forwardingThread.errorMessage(helper,
						String.format(
								bundle.getString("couldnt-parse-config-f"),
								configFile.getAbsolutePath()
						), e
				);
			}
		}
		forwardingThread.message(helper,
				String.format(
						bundle.getString("parsed-all-config-f"),
						helper.getAddonDirName()
				)
		);
		forwardingThread.stepFinish(helper, Step.ParseConfigs);
		return true;
	}

	private ResourceBundle getBundle() {
		return ResourceBundle.getBundle("com.kaylerrenslow.armaplugin.ArmaAddonsManagerBundle");
	}

	private static final ArmaAddonsManager instance = new ArmaAddonsManager();


	/**
	 * @param directory the directory to delete
	 * @return true if the directory doesn't exist or was deleted. Returns false if the directory couldn't be deleted
	 */
	private static boolean deleteDirectory(@NotNull File directory) {
		if (!directory.exists()) {
			return true;
		}
		File[] files = directory.listFiles();
		if (null != files) {
			for (File file : files) {
				if (file.isDirectory()) {
					deleteDirectory(file);
				} else {
					file.delete();
				}
			}
		}
		return directory.delete();
	}


	/**
	 * Used to parse addonscfg.xml file. This method will also parse any macros present in the xml. For instance, the macro
	 * $PROJECT_DIR$ will resolve to the project's directory. If the project's directory can't be resolved,
	 * "." will be the result of the macro.
	 *
	 * @param configFile the .xml config file
	 * @param project    the project instance
	 * @return the config, or null if couldn't be created
	 */
	@Nullable
	public static ArmaAddonsProjectConfig parseAddonsConfig(@NotNull File configFile, @NotNull Project project) {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = null;
		try {
			builder = factory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			e.printStackTrace();
			return null;
		}
		Document doc;
		try {
			doc = builder.parse(configFile);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		//https://stackoverflow.com/questions/13786607/normalization-in-dom-parsing-with-java-how-does-it-work
		doc.getDocumentElement().normalize();

		/*
		 * <?xml blah blah>
		 * <addons-cfg>
		 *     <roots>
		 *         <addons-root>D:\DATA\Steam\steamapps\common\Arma 3</addons-root>
		 *         <addons-root>$PROJECT_DIR$/addons</addons-root> <!-- There can be multiple addons roots-->
		 *     </roots>
		 *
		 *     <reference-dir>D:\DATA\Steam\steamapps\common\Arma 3\armaplugin</reference-dir> <!-- This is the place where addon's extract pbo contents are stored-->
		 *     <blacklist>
		 *         <addon>@Exile</addon> <!-- This refers to the directory name in addons-root-->
		 *     </blacklist>
		 *     <whitelist> <!-- If whitelist has no addons, everything will be used, except for blacklist's addons-->
		 *         <addon>@OPTRE</addon> <!-- This refers to the directory name in addons-root-->
		 *     </whitelist>
		 * </addons-cfg>
		 *
		 * MACROS:
		 * $PROJECT_DIR$: IntelliJ project directory
		 */

		List<String> blacklistedAddons = new ArrayList<>();
		List<String> whitelistedAddons = new ArrayList<>();
		List<String> addonsRoots = new ArrayList<>();
		String addonsReferenceDirectory = null;

		{ //get addons roots
			List<Element> addonRootContainerElements = XmlUtil.getChildElementsWithTagName(doc.getDocumentElement(), "roots");
			for (Element addonRootContainerElement : addonRootContainerElements) {
				List<Element> addonsRootElements = XmlUtil.getChildElementsWithTagName(addonRootContainerElement, "addons-root");
				for (Element addonsRootElement : addonsRootElements) {
					addonsRoots.add(XmlUtil.getImmediateTextContent(addonsRootElement));
				}
			}
		}
		{ //get blacklisted files
			List<Element> blacklistContainerElements = XmlUtil.getChildElementsWithTagName(doc.getDocumentElement(), "blacklist");
			for (Element blacklistContainerElement : blacklistContainerElements) {
				List<Element> addonElements = XmlUtil.getChildElementsWithTagName(blacklistContainerElement, "addon");
				for (Element addonElement : addonElements) {
					blacklistedAddons.add(XmlUtil.getImmediateTextContent(addonElement));
				}
			}
		}
		{ //get whitelisted files
			List<Element> whitelistContainerElements = XmlUtil.getChildElementsWithTagName(doc.getDocumentElement(), "whitelist");
			for (Element whitelistContainerElement : whitelistContainerElements) {
				List<Element> addonElements = XmlUtil.getChildElementsWithTagName(whitelistContainerElement, "addon");
				for (Element addonElement : addonElements) {
					whitelistedAddons.add(XmlUtil.getImmediateTextContent(addonElement));
				}
			}
		}
		{ //get addons reference directory
			List<Element> referenceDirElements = XmlUtil.getChildElementsWithTagName(doc.getDocumentElement(), "reference-dir");
			if (referenceDirElements.size() > 0) {
				addonsReferenceDirectory = XmlUtil.getImmediateTextContent(referenceDirElements.get(0));
			}
			if (addonsReferenceDirectory == null) {
				return null;
			}
		}

		{ //convert macros into their values
			Function<String, String> evalMacros = s -> {
				String projectDir = project.getBasePath();
				if (projectDir == null) {
					projectDir = ".";
				}
				return s.replaceAll("\\$PROJECT_DIR\\$", projectDir);
			};

			List<String> addonsRootsTemp = new ArrayList<>();
			for (String addonRoot : addonsRoots) {
				addonsRootsTemp.add(evalMacros.apply(addonRoot));
			}
			addonsRoots.clear();
			addonsRoots.addAll(addonsRootsTemp);

			addonsReferenceDirectory = evalMacros.apply(addonsReferenceDirectory);
		}

		return new ArmaAddonsProjectConfigImpl(blacklistedAddons, whitelistedAddons, addonsRoots, addonsReferenceDirectory);
	}

	private static class ArmaAddonImpl implements ArmaAddon {

		private final List<HeaderFile> configFiles;
		private final File addonDirectory;
		private final Map<String, String> defineMacros = new HashMap<>();
		private final File addonDirectoryInReferenceDirectory;

		public ArmaAddonImpl(@NotNull ArmaAddonHelper helper) {
			List<HeaderFile> parsedConfigs = new ArrayList<>(helper.getParseResults().size());
			this.configFiles = Collections.unmodifiableList(parsedConfigs);
			this.addonDirectory = helper.getAddonDirectory();

			for (HeaderParseResult result : helper.getParseResults()) {
				parsedConfigs.add(result.getFile());
				result.getDefineMacros().forEach((name, value) -> {
					defineMacros.put(name, value);
				});
			}
			this.addonDirectoryInReferenceDirectory = helper.getAddonDirectoryInReferenceDirectory();
		}

		@NotNull
		@Override
		public List<HeaderFile> getConfigFiles() {
			return configFiles;
		}

		@NotNull
		@Override
		public File getAddonDirectory() {
			return addonDirectory;
		}

		@NotNull
		@Override
		public File getAddonDirectoryInReferenceDirectory() {
			return addonDirectoryInReferenceDirectory;
		}

		@NotNull
		@Override
		public Map<String, String> getDefineMacros() {
			return defineMacros;
		}

		@Override
		public String toString() {
			return "ArmaAddonImpl{" +
					"configFiles=" + configFiles +
					", addonDirectory=" + addonDirectory +
					'}';
		}
	}

	private static class ArmaAddonHelper implements ArmaAddonIndexingHandle {
		private final File addonDirectory;
		private volatile double currentWorkProgress = 0;
		private volatile double totalWorkProgress = 0;
		private volatile boolean cancelled = false;
		private final List<HeaderParseResult> parseResults = new ArrayList<>();
		private File addonDirectoryInReferenceDirectory;

		public ArmaAddonHelper(@NotNull File addonDirectory) {
			this.addonDirectory = addonDirectory;
		}

		@NotNull
		public File getAddonDirectory() {
			return addonDirectory;
		}

		@NotNull
		public String getAddonDirName() {
			return addonDirectory.getName();
		}

		@NotNull
		public File getAddonDirectoryInReferenceDirectory() {
			return addonDirectoryInReferenceDirectory;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public double getCurrentWorkProgress() {
			return currentWorkProgress;
		}

		@Override
		public double getTotalWorkProgress() {
			return totalWorkProgress;
		}

		public void setCurrentWorkProgress(double d) {
			currentWorkProgress = d;
		}

		public void setTotalWorkProgress(double d) {
			totalWorkProgress = d;
		}

		@NotNull
		@Override
		public String getAddonName() {
			return getAddonDirName();
		}

		@NotNull
		public List<HeaderParseResult> getParseResults() {
			return parseResults;
		}

		public void setAddonDirectoryInReferenceDirectory(@NotNull File f) {
			this.addonDirectoryInReferenceDirectory = f;
		}
	}

	private static class ArmaAddonsProjectConfigImpl implements ArmaAddonsProjectConfig {

		@NotNull
		private final List<String> blacklistedAddons;
		@NotNull
		private final List<String> whitelistedAddons;
		@NotNull
		private final List<String> addonsRoots;
		@NotNull
		private final String addonsReferenceDirectory;

		public ArmaAddonsProjectConfigImpl(@NotNull List<String> blacklistedAddons,
										   @NotNull List<String> whitelistedAddons,
										   @NotNull List<String> addonsRoots,
										   @NotNull String addonsReferenceDirectory) {
			this.blacklistedAddons = blacklistedAddons;
			this.whitelistedAddons = whitelistedAddons;
			this.addonsRoots = addonsRoots;
			this.addonsReferenceDirectory = addonsReferenceDirectory;
		}

		@NotNull
		@Override
		public List<String> getBlacklistedAddons() {
			return blacklistedAddons;
		}

		@NotNull
		@Override
		public List<String> getWhitelistedAddons() {
			return whitelistedAddons;
		}

		@NotNull
		@Override
		public String getAddonsReferenceDirectory() {
			return addonsReferenceDirectory;
		}

		@NotNull
		@Override
		public List<String> getAddonsRoots() {
			return addonsRoots;
		}

		@Override
		public String toString() {
			return "ArmaAddonsProjectConfigImpl{" +
					"blacklist=" +
					blacklistedAddons +
					";whitelist=" +
					whitelistedAddons +
					";addonRoots=" +
					addonsRoots +
					";referenceDirectory=" +
					addonsReferenceDirectory +
					'}';
		}
	}

	private static class ArmaToolsWorkerThread extends Thread {
		public ArmaToolsWorkerThread(@NotNull Runnable target, int workerId) {
			super(target);
			setName("ArmaAddonsManager.ArmaToolsWorkerThread " + workerId);
		}
	}

	private static class ForwardingThread extends Thread implements ArmaAddonsIndexingCallback {
		private final LinkedBlockingQueue<Runnable> forwardingQ = new LinkedBlockingQueue<>();
		private final Runnable EXIT_THREAD = () -> {
		};
		@NotNull
		private final ArmaAddonsIndexingCallback callback;
		private final SimpleDateFormat dateFormat;
		private PrintWriter logger;
		private int loggerBufferSize = 0;
		private final Object loggerLock = new Object();
		private @Nullable
		File rootTempDirectory;

		public ForwardingThread(@NotNull ArmaAddonsIndexingCallback callback, @Nullable File logFile) {
			this.callback = callback;
			setName("ArmaAddonsManager - Callback Thread");
			if (logFile != null) {
				try {
					logger = new PrintWriter(logFile);
				} catch (IOException e) {
					logger = null;
				}
			}

			dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm aaa");
		}

		@Override
		public void run() {
			while (true) {
				try {
					Runnable take = forwardingQ.take();
					if (take == EXIT_THREAD) {
						return;
					}
					take.run();
				} catch (InterruptedException ignore) {
				}
			}
		}

		@Override
		public void indexStartedForAddon(@NotNull ArmaAddonIndexingHandle handle) {
			forwardingQ.add(() -> {
				callback.indexStartedForAddon(handle);
			});
		}

		@Override
		public void totalWorkProgressUpdate(@NotNull ArmaAddonIndexingHandle handle, double progress) {
			forwardingQ.add(() -> {
				callback.totalWorkProgressUpdate(handle, progress);
			});
		}

		@Override
		public void currentWorkProgressUpdate(@NotNull ArmaAddonIndexingHandle handle, double progress) {
			forwardingQ.add(() -> {
				callback.currentWorkProgressUpdate(handle, progress);
			});
		}

		@Override
		public void message(@NotNull ArmaAddonIndexingHandle handle, @NotNull String message) {
			log(message);
			forwardingQ.add(() -> {
				callback.message(handle, message);
			});
		}

		@Override
		public void errorMessage(@NotNull ArmaAddonIndexingHandle handle, @NotNull String message, @Nullable Exception e) {
			logError(message, e);
			forwardingQ.add(() -> {
				callback.errorMessage(handle, message, e);
			});
		}

		@Override
		public void errorMessage(@NotNull String message, @Nullable Exception e) {
			logError(message, e);
			forwardingQ.add(() -> {
				callback.errorMessage(message, e);
			});
		}

		@Override
		public void warningMessage(@NotNull ArmaAddonIndexingHandle handle, @NotNull String message, @Nullable Exception e) {
			logWarning(message, e);
			forwardingQ.add(() -> {
				callback.warningMessage(handle, message, e);
			});
		}

		@Override
		public void stepStart(@NotNull ArmaAddonIndexingHandle handle, @NotNull Step newStep) {
			forwardingQ.add(() -> {
				callback.stepStart(handle, newStep);
			});
		}

		@Override
		public void stepFinish(@NotNull ArmaAddonIndexingHandle handle, @NotNull Step stepFinished) {
			forwardingQ.add(() -> {
				callback.stepFinish(handle, stepFinished);
			});
		}

		@Override
		public void indexFinishedForAddon(@NotNull ArmaAddonIndexingHandle handle) {
			forwardingQ.add(() -> {
				callback.indexFinishedForAddon(handle);
			});
		}

		@Override
		public void finishedIndex() {
			forwardingQ.add(() -> {
				callback.finishedIndex();
			});
		}

		@Override
		public void startedIndex(@NotNull ArmaAddonsIndexingData data) {
			forwardingQ.add(() -> {
				callback.startedIndex(data);
			});
		}

		public void closeThread() {
			forwardingQ.add(EXIT_THREAD);
			if (logger != null) {
				synchronized (loggerLock) {
					try {
						logger.flush();
						logger.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		}

		public void log(@NotNull String message) {
			if (logger == null) {
				//don't need to synchronize this check since logger is immutable after constructor
				return;
			}

			synchronized (loggerLock) {
				logger.append(dateFormat.format(new Date(System.currentTimeMillis())));
				logger.append(" - ");
				logger.append(message);
				logger.append('\n');
				loggerBufferSize += message.length() + 1;//+1 for \n
				if (loggerBufferSize >= 1000) {
					logger.flush();
					loggerBufferSize = 0;
				}
			}
		}

		public void logError(@NotNull String message, @Nullable Exception e) {
			synchronized (loggerLock) {
				log("[ERROR] " + message);
				if (e != null) {
					log(ArmaPluginUtil.getExceptionString(e));
				}
			}
		}

		public void logWarning(@NotNull String message, @Nullable Exception e) {
			synchronized (loggerLock) {
				log("[WARNING] " + message);
				if (e != null) {
					log(ArmaPluginUtil.getExceptionString(e));
				}
			}
		}

		public void setRootTempDirectory(@NotNull File rootTempDirectory) {
			this.rootTempDirectory = rootTempDirectory;
		}

		@Nullable
		public File getRootTempDirectory() {
			return rootTempDirectory;
		}
	}
}
