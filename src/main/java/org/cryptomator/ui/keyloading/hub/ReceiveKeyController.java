package org.cryptomator.ui.keyloading.hub;

import com.nimbusds.jose.JWEObject;
import dagger.Lazy;
import org.cryptomator.common.vaults.Vault;
import org.cryptomator.ui.common.FxController;
import org.cryptomator.ui.common.FxmlFile;
import org.cryptomator.ui.common.FxmlScene;
import org.cryptomator.ui.keyloading.KeyLoading;
import org.cryptomator.ui.keyloading.KeyLoadingScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

@KeyLoadingScoped
public class ReceiveKeyController implements FxController {

	private static final Logger LOG = LoggerFactory.getLogger(ReceiveKeyController.class);
	private static final String SCHEME_PREFIX = "hub+";

	private final Stage window;
	private final HubConfig hubConfig;
	private final String deviceId;
	private final String bearerToken;
	private final CompletableFuture<ReceivedKey> result;
	private final Lazy<Scene> setupDeviceScene;
	private final Lazy<Scene> registerDeviceScene;
	private final Lazy<Scene> unauthorizedScene;
	private final URI vaultBaseUri;
	private final Lazy<Scene> invalidLicenseScene;
	private final HttpClient httpClient;

	@Inject
	public ReceiveKeyController(@KeyLoading Vault vault, ExecutorService executor, @KeyLoading Stage window, HubConfig hubConfig, @Named("deviceId") String deviceId, @Named("bearerToken") AtomicReference<String> tokenRef, CompletableFuture<ReceivedKey> result, @FxmlScene(FxmlFile.HUB_SETUP_DEVICE) Lazy<Scene> setupDeviceScene, @FxmlScene(FxmlFile.HUB_REGISTER_DEVICE) Lazy<Scene> registerDeviceScene, @FxmlScene(FxmlFile.HUB_UNAUTHORIZED_DEVICE) Lazy<Scene> unauthorizedScene, @FxmlScene(FxmlFile.HUB_INVALID_LICENSE) Lazy<Scene> invalidLicenseScene) {
		this.window = window;
		this.hubConfig = hubConfig;
		this.deviceId = deviceId;
		this.bearerToken = Objects.requireNonNull(tokenRef.get());
		this.result = result;
		this.setupDeviceScene = setupDeviceScene;
		this.registerDeviceScene = registerDeviceScene;
		this.unauthorizedScene = unauthorizedScene;
		this.vaultBaseUri = getVaultBaseUri(vault);
		this.invalidLicenseScene = invalidLicenseScene;
		this.window.addEventHandler(WindowEvent.WINDOW_HIDING, this::windowClosed);
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).executor(executor).build();
	}

	@FXML
	public void initialize() {
		requestUserToken();
	}

	/**
	 * STEP 1 (Request): GET user token for this vault
	 */
	private void requestUserToken() {
		var userTokenUri = appendPath(vaultBaseUri, "/user-tokens/me");
		var request = HttpRequest.newBuilder(userTokenUri) //
				.header("Authorization", "Bearer " + bearerToken) //
				.GET() //
				.build();
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII)) //
				.thenAcceptAsync(this::receivedUserTokenResponse, Platform::runLater) //
				.exceptionally(this::retrievalFailed);
	}

	/**
	 * STEP 1 (Response)
	 *
	 * @param response Response
	 */
	private void receivedUserTokenResponse(HttpResponse<String> response) {
		LOG.debug("GET {} -> Status Code {}", response.request().uri(), response.statusCode());
		try {
			switch (response.statusCode()) {
				case 200 -> requestDeviceToken(response.body());
				case 402 -> licenseExceeded();
				case 403 -> accessNotGranted();
				case 404 -> requestLegacyAccessToken();
				default -> throw new IOException("Unexpected response " + response.statusCode());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * STEP 2 (Request): GET device token for this user
	 */
	private void requestDeviceToken(String userToken) {
		var deviceTokenUri = appendPath(URI.create(hubConfig.devicesResourceUrl), "/%s/device-token".formatted(deviceId));
		var request = HttpRequest.newBuilder(deviceTokenUri) //
				.header("Authorization", "Bearer " + bearerToken) //
				.GET() //
				.build();
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII)) //
				.thenAcceptAsync(response -> receivedDeviceTokenResponse(userToken, response), Platform::runLater) //
				.exceptionally(this::retrievalFailed);
	}

	/**
	 * STEP 2 (Response)
	 *
	 * @param response Response
	 */
	private void receivedDeviceTokenResponse(String userToken, HttpResponse<String> response) {
		LOG.debug("GET {} -> Status Code {}", response.request().uri(), response.statusCode());
		try {
			switch (response.statusCode()) {
				case 200 -> receivedDeviceTokenSuccess(userToken, response.body());
				case 403, 404 -> needsDeviceSetup();
				default -> throw new IOException("Unexpected response " + response.statusCode());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void needsDeviceSetup() {
		window.setScene(setupDeviceScene.get());
	}

	private void receivedDeviceTokenSuccess(String rawUserToken, String rawDeviceToken) throws IOException {
		try {
			var userToken = JWEObject.parse(rawUserToken);
			var deviceToken = JWEObject.parse(rawDeviceToken);
			result.complete(ReceivedKey.userAndDeviceKey(userToken, deviceToken));
			window.close();
		} catch (ParseException e) {
			throw new IOException("Failed to parse JWE", e);
		}
	}

	/**
	 * LEGACY FALLBACK (Request): GET the legacy access token from Hub 1.x
	 */
	private void requestLegacyAccessToken() {
		var legacyAccessTokenUri = appendPath(vaultBaseUri, "/keys/%s".formatted(deviceId));
		var request = HttpRequest.newBuilder(legacyAccessTokenUri) //
				.header("Authorization", "Bearer " + bearerToken) //
				.GET() //
				.build();
		httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.US_ASCII)) //
				.thenAcceptAsync(this::receivedLegacyAccessTokenResponse, Platform::runLater) //
				.exceptionally(this::retrievalFailed);
	}

	/**
	 * LEGACY FALLBACK (Response)
	 *
	 * @param response Response
	 */
	private void receivedLegacyAccessTokenResponse(HttpResponse<String> response) {
		try {
			switch (response.statusCode()) {
				case 200 -> receivedLegacyAccessTokenSuccess(response.body());
				case 402 -> licenseExceeded();
				case 403 -> accessNotGranted();
				case 404 -> needsLegacyDeviceRegistration();
				default -> throw new IOException("Unexpected response " + response.statusCode());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void receivedLegacyAccessTokenSuccess(String rawToken) throws IOException {
		try {
			var token = JWEObject.parse(rawToken);
			result.complete(ReceivedKey.legacyDeviceKey(token));
			window.close();
		} catch (ParseException e) {
			throw new IOException("Failed to parse JWE", e);
		}
	}

	private void licenseExceeded() {
		window.setScene(invalidLicenseScene.get());
	}

	private void needsLegacyDeviceRegistration() {
		window.setScene(registerDeviceScene.get());
	}

	private void accessNotGranted() {
		window.setScene(unauthorizedScene.get());
	}

	private Void retrievalFailed(Throwable cause) {
		result.completeExceptionally(cause);
		return null;
	}

	@FXML
	public void cancel() {
		window.close();
	}

	private void windowClosed(WindowEvent windowEvent) {
		result.cancel(true);
	}

	private static URI appendPath(URI base, String path) {
		try {
			var newPath = base.getPath() + path;
			return new URI(base.getScheme(), base.getAuthority(), newPath, base.getQuery(), base.getFragment());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Can't append '" + path + "' to URI: " + base, e);
		}
	}

	private static URI getVaultBaseUri(Vault vault) {
		try {
			var kid = vault.getVaultConfigCache().get().getKeyId();
			assert kid.getScheme().startsWith(SCHEME_PREFIX);
			var hubUriScheme = kid.getScheme().substring(SCHEME_PREFIX.length());
			return new URI(hubUriScheme, kid.getSchemeSpecificPart(), kid.getFragment());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (URISyntaxException e) {
			throw new IllegalStateException("URI constructed from params known to be valid", e);
		}
	}
}
