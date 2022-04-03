package org.cryptomator.ui.fxapp;

import dagger.Lazy;
import org.cryptomator.common.settings.Settings;
import org.cryptomator.ui.traymenu.TrayMenuComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.awt.SystemTray;

@FxApplicationScoped
public class FxApplication {

	private static final Logger LOG = LoggerFactory.getLogger(FxApplication.class);

	private final Settings settings;
	private final AppLaunchEventHandler launchEventHandler;
	private final Lazy<TrayMenuComponent> trayMenu;
	private final FxApplicationWindows appWindows;
	private final FxApplicationStyle applicationStyle;
	private final FxApplicationTerminator applicationTerminator;
	private final AutoUnlocker autoUnlocker;

	@Inject
	FxApplication(Settings settings, AppLaunchEventHandler launchEventHandler, Lazy<TrayMenuComponent> trayMenu, FxApplicationWindows appWindows, FxApplicationStyle applicationStyle, FxApplicationTerminator applicationTerminator, AutoUnlocker autoUnlocker) {
		this.settings = settings;
		this.launchEventHandler = launchEventHandler;
		this.trayMenu = trayMenu;
		this.appWindows = appWindows;
		this.applicationStyle = applicationStyle;
		this.applicationTerminator = applicationTerminator;
		this.autoUnlocker = autoUnlocker;
	}

	public void start() {
		LOG.trace("FxApplication.start()");
		applicationStyle.initialize();
		appWindows.initialize();
		applicationTerminator.initialize();

		// init system tray
		final boolean hasTrayIcon;
		if (settings.showTrayIcon().get() && trayMenu.get().isSupported()) {
			trayMenu.get().initializeTrayIcon();
			Platform.setImplicitExit(false); // don't quit when closing all windows
			hasTrayIcon = true;
		} else {
			hasTrayIcon = false;
		}

		// show main window
		appWindows.showMainWindow().thenAccept(stage -> {
			if (settings.startHidden().get()) {
				if (hasTrayIcon) {
					stage.hide();
				} else {
					stage.setIconified(true);
				}
			}
		});

		launchEventHandler.startHandlingLaunchEvents();
		autoUnlocker.unlock();
	}

}
