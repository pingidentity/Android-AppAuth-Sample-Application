package com.pingidentity.developer.android_appauth_sample_application;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.AuthorizationServiceDiscovery;
import net.openid.appauth.CodeVerifierUtil;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.TokenResponse;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;


public class MainActivity extends AppCompatActivity {

    // TODO: Configure this to your environment
    // IDP Configuration

    // The OIDC issuer from which the configuration will be discovered. This is your base PingFederate server URL.
    private static final String OIDC_ISSUER = "https://sso.pingdevelopers.com";

    // The OAuth client ID. This is configured in your PingFederate administration console under OAuth Settings > Client Management.
    // The example "ac_client" from the OAuth playground can be used here.
    private static final String OIDC_CLIENT_ID = "ac_client";

    // The redirect URI that PingFederate will send the user back to after the authorization step. To avoid
    // collisions, this should be a reverse domain formatted string. You must define this in your OAuth client in PingFederate.
    private static final String OIDC_REDIRECT_URI = "com.pingidentity.developer.appauth://oidc_callback";

    // The scope to send in the request
    private static final String OIDC_SCOPE = "openid profile email";

    // Other constants
    // tag for logging
    private static final String TAG = "MainActivity";
    // key for authorization state
    private static final String KEY_AUTH_STATE = "com.pingidentity.developer.appauth.authState";
    private static final String KEY_USER_INFO = "userInfo";
    private static final String EXTRA_AUTH_SERVICE_DISCOVERY = "authServiceDiscovery";

    private static final int BUFFER_SIZE = 1024;

    private AuthState mAuthState;
    private AuthorizationService mAuthService;
    private JSONObject mUserInfoJson;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // TODO: DEV ONLY! Remove before deploying in production
        // For simplicity of the demo, all actions are performed on the main thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        mAuthService = new AuthorizationService(this);

        // Check for saved state first (may already have a session in the app)
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(KEY_AUTH_STATE)) {
                try {
                    mAuthState = AuthState.fromJson(savedInstanceState.getString(KEY_AUTH_STATE));
                } catch (JSONException ex) {
                    Log.e(TAG, "Malformed authorization JSON saved", ex);
                }
            }

            if (savedInstanceState.containsKey(KEY_USER_INFO)) {
                try {
                    mUserInfoJson = new JSONObject(savedInstanceState.getString(KEY_USER_INFO));
                } catch (JSONException ex) {
                    Log.e(TAG, "Failed to parse saved user info JSON", ex);
                }
            }
        }

        // Check for authorization callback
        Intent intent = getIntent();

        if (intent != null) {

            Log.d(TAG, "Intent received");

            if (mAuthState == null) {

                // Parse the authorization response
                AuthorizationResponse response = AuthorizationResponse.fromIntent(getIntent());
                AuthorizationException ex = AuthorizationException.fromIntent(getIntent());

                if (response != null || ex != null) {
                    mAuthState = new AuthState(response, ex);
                }

                if (response != null) {
                    Log.d(TAG, "Received AuthorizationResponse.");
                    exchangeAuthorizationCode(response);
                } else {
                    Log.i(TAG, "Authorization failed: " + ex);
                }
            }
        } else {
            Log.d(TAG, "NO Intent received");
        }

        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAuthService.dispose();
    }

    // redraw the UI after an action occurs
    public void refreshUi() {

        TextView textAccessToken = (TextView) findViewById(R.id.oauth2_access_token);
        TextView textRefreshToken = (TextView) findViewById(R.id.oauth2_refresh_token);
        TextView textIdToken = (TextView) findViewById(R.id.oidc_id_token);
        TextView textUserInfo = (TextView) findViewById(R.id.oidc_userinfo);
        Button buttonAuthorize = (Button) findViewById(R.id.button_authorize);
        Button buttonRefresh = (Button) findViewById(R.id.button_refresh);
        Button buttonUserInfo = (Button) findViewById(R.id.button_userinfo);

        buttonAuthorize.setVisibility(View.VISIBLE);

        if (mAuthState == null) {

            textAccessToken.setText(R.string.not_authorized);
            textRefreshToken.setText(R.string.not_authorized);
            textIdToken.setText(R.string.not_authorized);

            buttonRefresh.setVisibility(View.GONE);
            buttonUserInfo.setVisibility(View.GONE);
            return;
        }

        if (mAuthState.isAuthorized()) {

            buttonRefresh.setVisibility(View.VISIBLE);
            buttonUserInfo.setVisibility(View.VISIBLE);

            textAccessToken.setText((mAuthState.getAccessToken() != null)
                    ? mAuthState.getAccessToken()
                    : getString(R.string.no_token));

            textRefreshToken.setText((mAuthState.getRefreshToken() != null)
                    ? mAuthState.getRefreshToken()
                    : getString(R.string.no_token));

            textIdToken.setText((mAuthState.getIdToken() != null)
                    ? mAuthState.getIdToken()
                    : getString(R.string.no_token));

            if (mUserInfoJson != null) {
                try {
                    textUserInfo.setText(mUserInfoJson.toString(3));
                } catch (Exception ex) {
                    textUserInfo.setText(getString(R.string.json_error));
                }
            }

        } else {

            textAccessToken.setText(R.string.not_authorized);
            textRefreshToken.setText(R.string.not_authorized);
            textIdToken.setText(R.string.not_authorized);

            buttonRefresh.setVisibility(View.GONE);
            buttonUserInfo.setVisibility(View.GONE);
        }
    }

    // button action handlers
    public void requestAuthorization(View view) {

        final AuthorizationServiceConfiguration.RetrieveConfigurationCallback retrieveCallback =
                new AuthorizationServiceConfiguration.RetrieveConfigurationCallback() {

                    @Override
                    public void onFetchConfigurationCompleted(
                            @Nullable AuthorizationServiceConfiguration serviceConfiguration,
                            @Nullable AuthorizationException ex) {
                        if (ex != null) {
                            Log.w(TAG, "Failed to retrieve configuration for " + OIDC_ISSUER, ex);
                        } else {
                            Log.d(TAG, "configuration retrieved for " + OIDC_ISSUER
                                    + ", proceeding");
                            authorize(serviceConfiguration);
                        }
                    }
                };

        String discoveryEndpoint = OIDC_ISSUER + "/.well-known/openid-configuration";

        AuthorizationServiceConfiguration.fetchFromUrl(Uri.parse(discoveryEndpoint), retrieveCallback);
    }

    public void refreshToken(View view) {
        performTokenRequest(mAuthState.createTokenRefreshRequest());
    }

    public void getUserinfo(View view) {

        Log.d(TAG, "Calling Userinfo...");

        if (mAuthState.getAuthorizationServiceConfiguration() == null) {
            Log.e(TAG, "Cannot make userInfo request without service configuration");
        }

        mAuthState.performActionWithFreshTokens(mAuthService, new AuthState.AuthStateAction() {
            @Override
            public void execute(String accessToken, String idToken, AuthorizationException ex) {
                if (ex != null) {
                    Log.e(TAG, "Token refresh failed when fetching user info");
                    return;
                }

                AuthorizationServiceDiscovery discoveryDoc = getDiscoveryDocFromIntent(getIntent());
                if (discoveryDoc == null) {
                    throw new IllegalStateException("no available discovery doc");
                }

                URL userInfoEndpoint;
                try {
                    userInfoEndpoint = new URL(discoveryDoc.getUserinfoEndpoint().toString());
                } catch (MalformedURLException urlEx) {
                    Log.e(TAG, "Failed to construct user info endpoint URL", urlEx);
                    return;
                }

                InputStream userInfoResponse = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection) userInfoEndpoint.openConnection();
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    conn.setInstanceFollowRedirects(false);
                    userInfoResponse = conn.getInputStream();
                    String response = readStream(userInfoResponse);
                    updateUserInfo(new JSONObject(response));
                } catch (IOException ioEx) {
                    Log.e(TAG, "Network error when querying userinfo endpoint", ioEx);
                } catch (JSONException jsonEx) {
                    Log.e(TAG, "Failed to parse userinfo response");
                } finally {
                    if (userInfoResponse != null) {
                        try {
                            userInfoResponse.close();
                        } catch (IOException ioEx) {
                            Log.e(TAG, "Failed to close userinfo response stream", ioEx);
                        }
                    }
                }
            }
        });
    }


    // Kick off an authorization request
    private void authorize(AuthorizationServiceConfiguration authServiceConfiguration) {

        // NOTE: Required for PingFederate 8.1 and below for the .setCodeVerifier() option below
        // to generate "plain" code_challenge_method these versions of PingFederate do not support
        // S256 PKCE.
        String codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier();

        // OPTIONAL: Add any additional parameters to the authorization request
        HashMap<String, String> additionalParams = new HashMap<>();
        additionalParams.put("acr_values", "urn:acr:form");

        AuthorizationRequest authRequest = new AuthorizationRequest.Builder(
                authServiceConfiguration,
                OIDC_CLIENT_ID,
                AuthorizationRequest.RESPONSE_TYPE_CODE,
                Uri.parse(OIDC_REDIRECT_URI))
                .setScope(OIDC_SCOPE)
                .setCodeVerifier(codeVerifier, codeVerifier, "plain")
                .setAdditionalParameters(additionalParams)
                .build();

        Log.d(TAG, "Making auth request to " + authServiceConfiguration.authorizationEndpoint);
        mAuthService.performAuthorizationRequest(
                authRequest,
                createPostAuthorizationIntent(
                        this.getApplicationContext(),
                        authRequest,
                        authServiceConfiguration.discoveryDoc));
    }

    private void exchangeAuthorizationCode(AuthorizationResponse authorizationResponse) {

        performTokenRequest(authorizationResponse.createTokenExchangeRequest());
    }

    private void performTokenRequest(TokenRequest tokenRequest) {

        mAuthService.performTokenRequest(
                tokenRequest,
                new AuthorizationService.TokenResponseCallback()

                {
                    @Override
                    public void onTokenRequestCompleted (
                            @Nullable TokenResponse tokenResponse,
                            @Nullable AuthorizationException ex){
                        receivedTokenResponse(tokenResponse, ex);
                    }
                }

        );
    }


    private void receivedTokenResponse(
            @Nullable TokenResponse tokenResponse,
            @Nullable AuthorizationException authException) {
        Log.d(TAG, "Token request complete");
        mAuthState.update(tokenResponse, authException);
        refreshUi();
    }

    private PendingIntent createPostAuthorizationIntent(
            @NonNull Context context,
            @NonNull AuthorizationRequest request,
            @Nullable AuthorizationServiceDiscovery discoveryDoc) {

        Intent intent = new Intent(context, this.getClass());
        if (discoveryDoc != null) {
            intent.putExtra(EXTRA_AUTH_SERVICE_DISCOVERY, discoveryDoc.docJson.toString());
        }

        return PendingIntent.getActivity(context, request.hashCode(), intent, 0);
    }

    private AuthorizationServiceDiscovery getDiscoveryDocFromIntent(Intent intent) {
        if (!intent.hasExtra(EXTRA_AUTH_SERVICE_DISCOVERY)) {
            return null;
        }
        String discoveryJson = intent.getStringExtra(EXTRA_AUTH_SERVICE_DISCOVERY);
        try {
            return new AuthorizationServiceDiscovery(new JSONObject(discoveryJson));
        } catch (JSONException | AuthorizationServiceDiscovery.MissingArgumentException  ex) {
            throw new IllegalStateException("Malformed JSON in discovery doc");
        }
    }

    private void updateUserInfo(final JSONObject jsonObject) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                mUserInfoJson = jsonObject;
                refreshUi();
            }
        });
    }

    private static String readStream(InputStream stream) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(stream));
        char[] buffer = new char[BUFFER_SIZE];
        StringBuilder sb = new StringBuilder();
        int readCount;
        while ((readCount = br.read(buffer)) != -1) {
            sb.append(buffer, 0, readCount);
        }
        return sb.toString();
    }
}
