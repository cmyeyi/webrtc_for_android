package cc.rome.vv;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.SessionDescription;

import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SignalingClient {

    private static SignalingClient instance;
    private String room = CommonConfig.ROOM_TEST_ID;
    private Socket socket;
    private Callback callback;


    private SignalingClient() {
    }

    public static SignalingClient get() {
        if (instance == null) {
            synchronized (SignalingClient.class) {
                if (instance == null) {
                    instance = new SignalingClient();
                }
            }
        }
        return instance;
    }

    private final TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {

                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
    };

    public void init(Callback callback) {
        this.callback = callback;
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, null);
            IO.setDefaultHostnameVerifier((hostname, session) -> true);
            IO.setDefaultSSLContext(sslContext);

            socket = IO.socket(CommonConfig.HTTPS_SERVER);
            socket.connect();

            socket.emit("create or join", room);
            socket.on("created", args -> {
                String roomId = String.valueOf(args[0]);
                String socketId = String.valueOf(args[1]);
                Log.e("#####[created]", "room created，roomId=" + roomId + ",selfSocketId = " + socketId);
                callback.onCreateRoom(roomId, socketId);
            });
            socket.on("full", args -> {
                Log.e("#####[full]", "room full");
            });
            socket.on("join", args -> {
                String roomId = String.valueOf(args[0]);
                String socketId = String.valueOf(args[1]);
                Log.e("#####[join]", socketId + " join + 房间" + roomId);
                //服务端：io.sockets.in(room).emit('join', room, socket.id);
                //这里收到服务端发射过来的join消息，取出第二个参数，第一个参数是房间号room，第二个参数是socket.id
                callback.onPeerJoined(roomId, socketId);
            });
            socket.on("joined", args -> {
                String roomId = String.valueOf(args[0]);
                String socketId = String.valueOf(args[1]);
                Log.e("#####", "self joined, 您已经加入到room " + roomId);
                callback.onSelfJoined(roomId, socketId);//针对的是非房间创建者
            });
            socket.on("log", args -> {
                Log.e("#####", "log call " + Arrays.toString(args));
            });
            socket.on("ready", args -> {
                Log.e("#####[ready]", "###############");
            });
            socket.on("bye", args -> {
                String roomId = String.valueOf(args[0]);
                String socketId = String.valueOf(args[1]);
                Log.e("#####[bye]", "socketId " + socketId + "离开房间 " + roomId);
                callback.onPeerLeave(roomId, socketId);
            });
            socket.on("message", args -> {
                Log.e("#####", "message:" + Arrays.toString(args));
                Object arg = args[0];
                if (arg instanceof String) {
                } else if (arg instanceof JSONObject) {
                    JSONObject data = (JSONObject) arg;
                    String type = data.optString("type");
                    if ("offer".equals(type)) {
                        callback.onOfferReceived(data);
                    } else if ("answer".equals(type)) {
                        callback.onAnswerReceived(data);
                    } else if ("candidate".equals(type)) {
                        callback.onIceCandidateReceived(data);
                    }
                }
            });
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyManagementException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    public void leave(String roomId) {
        socket.emit("bye", roomId);
        socket.disconnect();
        socket.close();
        instance = null;
    }


    public void sendIceCandidate(IceCandidate iceCandidate, String to) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "candidate");
            jo.put("label", iceCandidate.sdpMLineIndex);
            jo.put("id", iceCandidate.sdpMid);
            jo.put("candidate", iceCandidate.sdp);
            jo.put("from", socket.id());
            jo.put("to", to);

            socket.emit("message", jo);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void sendSessionDescription(SessionDescription sdp, String to) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", sdp.type.canonicalForm());
            jo.put("sdp", sdp.description);
            jo.put("from", socket.id());
            jo.put("to", to);
            socket.emit("message", jo);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public interface Callback {
        void onCreateRoom(String room, String socketId);

        void onPeerJoined(String room, String socketId);

        void onSelfJoined(String room, String socketId);

        void onPeerLeave(String roomId, String socketId);

        void onOfferReceived(JSONObject data);

        void onAnswerReceived(JSONObject data);

        void onIceCandidateReceived(JSONObject data);
    }

}
