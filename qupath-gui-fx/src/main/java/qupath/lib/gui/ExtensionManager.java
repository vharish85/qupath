/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */


package qupath.lib.gui;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableMap;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import qupath.lib.common.Version;
import qupath.lib.gui.actions.ActionTools;
import qupath.lib.gui.commands.Commands;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.extensions.UpdateChecker;
import qupath.lib.gui.localization.QuPathResources;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.gui.viewer.DragDropImportListener;
import qupath.lib.images.servers.ImageServerBuilder;
import qupath.lib.images.servers.ImageServerProvider;


/**
 * Manage loading extensions for a QuPathGUI instance.
 *
 * @author Pete Bankhead
 *
 * @since v0.5.0
 */
public class ExtensionManager {

	private static final Logger logger = LoggerFactory.getLogger(ExtensionManager.class);

	private final QuPathGUI qupath;

	private final ObservableMap<Class<? extends QuPathExtension>, QuPathExtension> loadedExtensions = FXCollections.observableHashMap();

	private final BooleanProperty refreshingExtensions = new SimpleBooleanProperty(false);
	
	private ExtensionManager(QuPathGUI qupath) {
		this.qupath = qupath;
	}
	
	static ExtensionManager create(QuPathGUI qupath) {
		return new ExtensionManager(qupath);
	}
	
	/**
	 * @return a collection of extensions that are currently loaded
	 */
	public ObservableMap<Class<? extends QuPathExtension>, QuPathExtension> getLoadedExtensions() {
		return loadedExtensions;
	}
	
	/**
	 * Property indicating whether extensions are in the process of being refreshed.
     */
	public ReadOnlyBooleanProperty refreshingExtensions() {
		return BooleanProperty.readOnlyBooleanProperty(refreshingExtensions);
	}
	
	/**
	 * Check the extensions directory, loading any new extensions found there.
	 * @param showNotification if true, display a notification if a new extension has been loaded
	 */
	public synchronized void refreshExtensions(final boolean showNotification) {
		
		if ("true".equalsIgnoreCase(System.getProperty("noextensions"))) {
			logger.info("Extensions will be skipped - 'noextensions' system property is set");
			return;
		}
		
		refreshingExtensions.set(true);
		
		// Refresh the extensions
		var extensionClassLoader = getExtensionClassLoader();
		extensionClassLoader.refresh();
		
		var extensionLoader = ServiceLoader.load(QuPathExtension.class, extensionClassLoader);

		// Sort the extensions by name, to ensure predictable loading order
		// (also, menus are in a better order if ImageJ extension installed before OpenCV extension)
		List<QuPathExtension> extensions = new ArrayList<>();
		Iterator<QuPathExtension> iterator = extensionLoader.iterator();
		while (iterator.hasNext()) {
			try {
				extensions.add(iterator.next());
			} catch (Throwable e) {
				if (qupath.getStage() != null && qupath.getStage().isShowing()) {
					Dialogs.showErrorMessage("Extension error", "Error loading extension - check 'View -> Show log' for details.");
				}
				logger.error(e.getLocalizedMessage(), e);
			}
		}
		extensions.sort(Comparator.comparing(QuPathExtension::getName));
		Version qupathVersion = QuPathGUI.getVersion();
		for (QuPathExtension extension : extensions) {
			if (!loadedExtensions.containsKey(extension.getClass())) {
				Version version = extension.getVersion();
				try {
					long startTime = System.currentTimeMillis();
					extension.installExtension(qupath);
					long endTime = System.currentTimeMillis();
					logger.info("Loaded extension {} ({} ms)", extension.getName(), endTime - startTime);
					if (version != null)
						logger.debug("{} was written for QuPath {}", extension.getName(), version);
					else
						logger.debug("{} does not report a compatible QuPath version", extension.getName());						
					loadedExtensions.put(extension.getClass(), extension);
					if (showNotification)
						Dialogs.showInfoNotification("Extension loaded",  extension.getName());
				} catch (Exception | LinkageError e) {
					String message = "Unable to load " + extension.getName();
					if (showNotification)
						Dialogs.showErrorNotification("Extension error", message);
					logger.error("Error loading extension " + extension + ": " + e.getLocalizedMessage(), e);
					if (!Objects.equals(qupathVersion, version)) {
						if (version == null)
							logger.warn("QuPath version for which the '{}' was written is unknown!", extension.getName());
						else if (version.equals(qupathVersion))
							logger.warn("'{}' reports that it is compatible with the current QuPath version {}", extension.getName(), qupathVersion);
						else
							logger.warn("'{}' was written for QuPath {} but current version is {}", extension.getName(), version, qupathVersion);
					}
					try {
						logger.error("It is recommended that you delete {} and restart QuPath",
								URLDecoder.decode(
										extension.getClass().getProtectionDomain().getCodeSource().getLocation().toExternalForm(),
										StandardCharsets.UTF_8));
					} catch (Exception e2) {
						logger.debug("Error finding code source " + e2.getLocalizedMessage(), e2);
					}
					qupath.getCommonActions().SHOW_LOG.handle(null);
				}
			}
		}
		// Set the ImageServer to also look on the same search path
		List<ImageServerBuilder<?>> serverBuildersBefore = ImageServerProvider.getInstalledImageServerBuilders();
		ImageServerProvider.setServiceLoader(ServiceLoader.load(ImageServerBuilder.class, extensionClassLoader));
		if (showNotification) {
			// A bit convoluted... but try to show new servers that have been loaded by comparing with the past
			List<String> serverBuilders = serverBuildersBefore
					.stream().map(ImageServerBuilder::getName)
					.toList();
			List<String> serverBuildersUpdated = ImageServerProvider.getInstalledImageServerBuilders()
					.stream().map(ImageServerBuilder::getName)
					.collect(Collectors.toCollection(ArrayList::new));
			serverBuildersUpdated.removeAll(serverBuilders);
			for (String builderName : serverBuildersUpdated) {
				Dialogs.showInfoNotification("Image server loaded",  builderName);
			}
		}
		refreshingExtensions.set(false);
	}
	
	
	private ExtensionClassLoader getExtensionClassLoader() {
		return ExtensionClassLoader.getInstance();
	}
	
	
	
	/**
	 * Copy a collection of files to QuPath's extensions directory, notifying the user about
	 * what is done and prompting to create a user directory if needed.
	 * 
	 * @param files a collection of jar files for installation
	 */
	public void promptToCopyFilesToExtensionsDirectory(final Collection<File> files) {
		if (files.isEmpty()) {
			logger.debug("No extensions to install!");
			return;
		}

		var extensionClassLoader = getExtensionClassLoader();
		var dir = extensionClassLoader.getExtensionDirectory();
		
		if (dir == null || !Files.isDirectory(dir)) {
			logger.info("No extension directory found!");
			var dirUser = Commands.requestUserDirectory(true);
			if (dirUser == null)
				return;
			dir = extensionClassLoader.getExtensionDirectory();
		}
		// Create directory if we need it
		if (!Files.exists(dir)) {
			logger.info("Creating extensions directory: {}", dir);
			try {
				Files.createDirectories(dir);
			} catch (IOException e) {
				Dialogs.showErrorMessage("Install extensions", "Error trying to install extensions: " + e.getLocalizedMessage());
				logger.error(e.getLocalizedMessage(), e);
			}
		}
		
		// Copy all files into extensions directory
		for (File file : files) {
			Path source = file.toPath();
			Path destination = dir.resolve(source.getFileName());
			if (destination.toFile().exists()) {
				// It would be better to check how many files will be overwritten in one go,
				// but this should be a pretty rare occurrence
				if (!Dialogs.showConfirmDialog("Install extension", "Overwrite " + destination.toFile().getName() + "?\n\nYou will have to restart QuPath to see the updates."))
					return;
			}
			try {
				Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Dialogs.showErrorMessage("Extension error", file + "\ncould not be copied, sorry");
				logger.error("Could not copy file {}", file, e);
				return;
			}
		}
		refreshExtensions(true);
	}


	/**
	 * Borderpane that displays extensions, with options to remove,
	 * open containing folder, update, where possible.
	 */
	static class ExtensionManagerController extends BorderPane {

		private static final Logger logger = LoggerFactory.getLogger(QuPathGUI.class);

		@FXML
		private ListView<QuPathExtension> listView;

		@FXML
		private Button addBtn;

		@FXML
		private Button rmBtn;
		@FXML
		private Button disableBtn;
		@FXML
		private Button submitBtn;
		@FXML
		private Button openExtensionDirBtn;

		@FXML
		private Button updateBtn;

		@FXML
		private VBox topVBox;
		@FXML
		private HBox addHBox;

		@FXML
		private TextField ownerTextArea;
		@FXML
		private TextField repoTextArea;

		/**
		 * Create an instance of the ExtensionManager UI pane.
		 * @return A BorderPane subclass.
		 * @throws IOException If FXML or resources can't be found.
		 */
		static ExtensionManagerController createInstance() throws IOException {
			return new ExtensionManagerController();
		}

		private ExtensionManagerController() throws IOException {
			var loader = new FXMLLoader(ExtensionManagerController.class.getResource("ExtensionManager.fxml"));
			loader.setController(this);
			loader.setRoot(this);
			loader.setResources(ResourceBundle.getBundle("qupath/lib/gui/localization/qupath-gui-strings"));
			loader.load();
		}


		@FXML
		private void initialize() {
			this.setOnDragDropped(QuPathGUI.getInstance().getDefaultDragDropListener());
			ExtensionManager extensionManager = QuPathGUI.getInstance().getExtensionManager();
			ObservableMap<Class<? extends QuPathExtension>, QuPathExtension> extensions = extensionManager.getLoadedExtensions();
			extensions.addListener((MapChangeListener<Class<? extends QuPathExtension>, QuPathExtension>) c -> {
				if (c.wasAdded()) {
					listView.getItems().add(c.getValueAdded());
				}
				if (c.wasRemoved()) {
					listView.getItems().remove(c.getValueRemoved());
				}
			});

			openExtensionDirBtn.disableProperty().bind(
					UserDirectoryManager.getInstance().userDirectoryProperty().isNull());

			var items = listView.getItems();
			items.addAll(
					extensionManager.getLoadedExtensions().values()
							.stream()
							.sorted(Comparator.comparing(QuPathExtension::getName))
							.toList());
			listView.setCellFactory(param -> new ExtensionListCell(param));
			ownerTextArea.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
				if (e.getCode() == KeyCode.ENTER) {
					submitAdd();
				}
				if (e.getCode() == KeyCode.ESCAPE) {
					cancelAdd();
				}
			});
			repoTextArea.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
				if (e.getCode() == KeyCode.ENTER) {
					submitAdd();
				}
				if (e.getCode() == KeyCode.ESCAPE) {
					cancelAdd();
				}
			});
		}

		@FXML
		private void addExtension() {
			if (!topVBox.getChildren().contains(addHBox)) {
				topVBox.getChildren().add(addHBox);
			}
		}

		@FXML
		private void submitAdd() {
			var repo = GitHubProject.GitHubRepo.create("tmp", ownerTextArea.getText(), repoTextArea.getText());
			try {
				DragDropImportListener.askToDownload(repo);
			} catch (URISyntaxException | IOException | InterruptedException e) {
				Dialogs.showErrorNotification(QuPathResources.getString("ExtensionManager.unableToDownload"), e);
			}
			cancelAdd();
		}

		@FXML
		private void cancelAdd() {
			ownerTextArea.clear();
			repoTextArea.clear();
			topVBox.getChildren().remove(addHBox);
		}

		@FXML
		private void openExtensionDir() {
			var dir = ExtensionClassLoader.getInstance().getExtensionDirectory();
			if (dir != null) {
				GuiTools.browseDirectory(dir.toFile());
			} else {
				Dialogs.showErrorNotification(
						QuPathResources.getString("ExtensionManager"),
						QuPathResources.getString("ExtensionManager.noExtensionDirectorySet"));
			}
		}

		private static void removeExtension(QuPathExtension extension) {
			if (extension == null) {
				logger.info("No extension selected, so none can be removed");
				return;
			}
			if (!Dialogs.showYesNoDialog(
					QuPathResources.getString("ExtensionManager"),
					String.format(QuPathResources.getString("ExtensionManager.confirmRemoveExtension"), extension.getName()))) {
				return;
			}
			try {
				var url = extension.getClass().getProtectionDomain().getCodeSource().getLocation();
				logger.info("Removing extension: {}", url);
				new File(url.toURI().getPath()).delete();
				Dialogs.showInfoNotification(
						QuPathResources.getString("ExtensionManager"),
						String.format(QuPathResources.getString("ExtensionManager.extensionRemoved"), url));
			} catch (URISyntaxException e) {
				logger.error("Exception removing extension: " + extension, e);
			}
		}

		private static void updateExtension(QuPathExtension extension) {
			if (!(extension instanceof GitHubProject project)) {
				Dialogs.showWarningNotification(QuPathResources.getString("ExtensionManager"),
						QuPathResources.getString("ExtensionManager.unableToCheckForUpdates"));
				return;
			}
			var version = extension.getVersion();
			try {
				var release = UpdateChecker.checkForUpdate(project.getRepository());
				if (release != null && release.getVersion() != Version.UNKNOWN && version.compareTo(release.getVersion()) < 0) {
					logger.info("Found newer release for {} ({} -> {})", project.getRepository().getName(), version, release.getVersion());
					DragDropImportListener.askToDownload(project.getRepository());
				} else if (release != null) {
					Dialogs.showInfoNotification(
							QuPathResources.getString("ExtensionManager"),
							String.format(
									QuPathResources.getString("ExtensionManager.noNewerRelease"),
									project.getRepository().getName(), version, release.getVersion())
					);
				}
			} catch (IOException | URISyntaxException | InterruptedException e) {
				Dialogs.showErrorNotification(QuPathResources.getString("ExtensionManager.unableToUpdate"), e);
			}
		}

		/**
		 * Controller class for extension list cells
		 */
		static class ExtensionListCell extends ListCell<QuPathExtension> {

			public ExtensionListCell(ListView<QuPathExtension> listView) {
				super();
			}

			@Override
			public void updateItem(QuPathExtension item, boolean empty) {
				super.updateItem(item, empty);
				if (empty || item == null) {
					setText(null);
					setGraphic(null);
					return;
				}
				if (!(item instanceof GitHubProject)) {
					setMouseTransparent(true);
					setFocusTraversable(false);
					setDisable(true);
				}
				ExtensionListCellVBox vbox = new ExtensionListCellVBox(item);

				vbox.rmBtn.setGraphic(IconFactory.createNode(8, 8, IconFactory.PathIcons.MINUS));
				vbox.updateBtn.setGraphic(IconFactory.createNode(8, 8, IconFactory.PathIcons.REFRESH));
				vbox.nameText.setText(item.getName());
				vbox.versionText.setText(item.getVersion().toString());
				vbox.descriptionText.setText(WordUtils.wrap(item.getDescription(), 80));

				setGraphic(vbox);
				var contextMenu = new ContextMenu();
				contextMenu.getItems().add(ActionTools.createMenuItem(
						ActionTools.createAction(() -> openContainingFolder(item),
								QuPathResources.getString("ExtensionManager.openContainingFolder"))));
				contextMenu.getItems().add(ActionTools.createMenuItem(
						ActionTools.createAction(() -> updateExtension(item),
								QuPathResources.getString("ExtensionManager.updateExtension"))));
				contextMenu.getItems().add(ActionTools.createMenuItem(
						ActionTools.createAction(() -> removeExtension(item),
								QuPathResources.getString("ExtensionManager.removeExtension"))));
				this.setContextMenu(contextMenu);

				var tooltipText = item.getName() + "\n" + QuPathResources.getString("ExtensionManager.doubleClick");
				Tooltip.install(this, new Tooltip(tooltipText));
				setOnMouseClicked(event -> {
					if (event.getClickCount() == 2 && event.getButton() == MouseButton.PRIMARY) {
						if (item instanceof GitHubProject) { // this should always be true anyway...
							String url = ((GitHubProject)item).getRepository().getUrlString();
							try {
								logger.info("Trying to open URL {}", url);
								GuiTools.browseURI(new URI(url));
							} catch (URISyntaxException e) {
								Dialogs.showErrorNotification(
										QuPathResources.getString("ExtensionManager.unableToOpenGitHubURL") + url,
										e);
							}
						}
					}
				});
			}

			private void openContainingFolder(QuPathExtension extension) {
				var url = extension.getClass().getProtectionDomain().getCodeSource().getLocation();
				try {
					GuiTools.browseDirectory(new File(url.toURI()));
				} catch (URISyntaxException e) {
					logger.error("Unable to open directory {}", url);
				}
			}
		}

		/**
		 * Simple class that just loads the FXML for a list cell
		 */
		static class ExtensionListCellVBox extends VBox {
			private final QuPathExtension extension;
			@FXML
			private Button rmBtn;
			@FXML
			private Button updateBtn;
			@FXML
			private Text nameText, versionText, descriptionText;
			ExtensionListCellVBox(QuPathExtension extension) {
				this.extension = extension;
				var loader = new FXMLLoader(ExtensionManagerController.class.getResource("ExtensionListCellVBox.fxml"));
				loader.setController(this);
				loader.setRoot(this);
				loader.setResources(ResourceBundle.getBundle("qupath/lib/gui/localization/qupath-gui-strings"));
				try {
					loader.load();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}

			@FXML
			private void updateExtension() {
				ExtensionManagerController.updateExtension(this.extension);
			}
			@FXML
			private void removeExtension() {
				ExtensionManagerController.removeExtension(this.extension);
			}
		}

	}

	/**
	 * Create a BorderPane for displaying/managing extensions.
	 * @return A BorderPane for displaying and managing QuPath extensions.
	 */
	public static BorderPane getManagerPane() throws IOException {
		return ExtensionManagerController.createInstance();
	}

}
