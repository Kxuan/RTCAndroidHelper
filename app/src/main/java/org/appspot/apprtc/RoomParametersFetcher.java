/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.appspot.apprtc;

import org.appspot.apprtc.AppRTCClient.SignalingParameters;
import org.appspot.apprtc.util.AsyncHttpURLConnection;
import org.appspot.apprtc.util.AsyncHttpURLConnection.AsyncHttpEvents;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.Scanner;

/**
 * AsyncTask that converts an AppRTC room URL into the set of signaling
 * parameters to use with that room.
 */
public class RoomParametersFetcher {
    private static final String TAG = "RoomRTCClient";
    private static final int TURN_HTTP_TIMEOUT_MS = 5000;
    private final RoomParametersFetcherEvents events;
    private final String roomUrl;
    private final String roomMessage;
    private AsyncHttpURLConnection httpConnection;

    /**
     * Room parameters fetcher callbacks.
     */
    public static interface RoomParametersFetcherEvents {
        /**
         * Callback fired once the room's signaling parameters
         * SignalingParameters are extracted.
         */
        public void onSignalingParametersReady(final SignalingParameters params);

        /**
         * Callback for room parameters extraction error.
         */
        public void onSignalingParametersError(final String description);
    }

    public RoomParametersFetcher(String roomUrl, String roomMessage,
                                 final RoomParametersFetcherEvents events) {
        this.roomUrl = roomUrl;
        this.roomMessage = roomMessage;
        this.events = events;
    }

    public void makeRequest() {
        Log.d(TAG, "Connecting to room: " + roomUrl);
        httpConnection = new AsyncHttpURLConnection(
                "POST", roomUrl, roomMessage,
                new AsyncHttpEvents() {
                    @Override
                    public void onHttpError(String errorMessage) {
                        Log.e(TAG, "Room connection error: " + errorMessage);
                        events.onSignalingParametersError(errorMessage);
                    }

                    @Override
                    public void onHttpComplete(String response) {
                        roomHttpResponseParse(response);
                    }
                });
        //向中心服务器发送连接信息
        httpConnection.send();
    }

    //解析zhongxin服务器应答的
    //POST URL:
    //  https://<room_server_address>/join/<room_id>
    private void roomHttpResponseParse(String response) {
        Log.d(TAG, "Room response: " + response);
        try {
            LinkedList<IceCandidate> iceCandidates = null;
            SessionDescription offerSdp = null;
            JSONObject roomJson = new JSONObject(response);

            long roomId = roomJson.getLong("room_id");
            long clientId = roomJson.getLong("client_id");
            String wssUrl = roomJson.getString("wss_url");
            String wssPostUrl = roomJson.getString("wss_post_url");

            Log.d(TAG, "RoomId: " + roomId + ". ClientId: " + clientId);
            Log.d(TAG, "WSS url: " + wssUrl);
            Log.d(TAG, "WSS POST url: " + wssPostUrl);

            LinkedList<PeerConnection.IceServer> iceServers =
                    iceServersFromPCConfigJSON(roomJson.getString("pc_config"));
            boolean isTurnPresent = false;
            for (PeerConnection.IceServer server : iceServers) {
                Log.d(TAG, "IceServer: " + server);
                if (server.uri.startsWith("turn:")) {
                    isTurnPresent = true;
                    break;
                }
            }
            // Request TURN servers.
            if (!isTurnPresent) {
                LinkedList<PeerConnection.IceServer> turnServers =
                        requestTurnServers(roomJson.getString("turn_url"));
                for (PeerConnection.IceServer turnServer : turnServers) {
                    Log.d(TAG, "TurnServer: " + turnServer);
                    iceServers.add(turnServer);
                }
            }

            SignalingParameters params = new SignalingParameters(
                    iceServers,
                    clientId, wssUrl, wssPostUrl,
                    offerSdp, iceCandidates);
            events.onSignalingParametersReady(params);
        } catch (JSONException e) {
            events.onSignalingParametersError(
                    "Room JSON parsing error: " + e.toString());
        } catch (IOException e) {
            events.onSignalingParametersError("Room IO error: " + e.toString());
        }
    }

    // Requests & returns a TURN ICE Server based on a request URL.  Must be run
    // off the main thread!
    private LinkedList<PeerConnection.IceServer> requestTurnServers(String url)
            throws IOException, JSONException {
        LinkedList<PeerConnection.IceServer> turnServers =
                new LinkedList<PeerConnection.IceServer>();
        Log.d(TAG, "Request TURN from: " + url);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TURN_HTTP_TIMEOUT_MS);
        connection.setReadTimeout(TURN_HTTP_TIMEOUT_MS);
        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Non-200 response when requesting TURN server from "
                    + url + " : " + connection.getHeaderField(null));
        }
        InputStream responseStream = connection.getInputStream();
        String response = drainStream(responseStream);
        connection.disconnect();
        Log.d(TAG, "TURN response: " + response);
        JSONObject responseJSON = new JSONObject(response);
        String username = responseJSON.getString("username");
        String password = responseJSON.getString("password");
        JSONArray turnUris = responseJSON.getJSONArray("uris");
        for (int i = 0; i < turnUris.length(); i++) {
            String uri = turnUris.getString(i);
            turnServers.add(new PeerConnection.IceServer(uri, username, password));
        }
        return turnServers;
    }

    // Return the list of ICE servers described by a WebRTCPeerConnection
    // configuration string.
    private LinkedList<PeerConnection.IceServer> iceServersFromPCConfigJSON(
            String pcConfig) throws JSONException {
        JSONObject json = new JSONObject(pcConfig);
        JSONArray servers = json.getJSONArray("iceServers");
        LinkedList<PeerConnection.IceServer> ret =
                new LinkedList<PeerConnection.IceServer>();
        for (int i = 0; i < servers.length(); ++i) {
            JSONObject server = servers.getJSONObject(i);
            String url = server.getString("urls");
            String credential =
                    server.has("credential") ? server.getString("credential") : "";
            ret.add(new PeerConnection.IceServer(url, "", credential));
        }
        return ret;
    }

    // Return the contents of an InputStream as a String.
    private static String drainStream(InputStream in) {
        Scanner s = new Scanner(in).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

}
