package cc.rome.vv;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SignalingClient.Callback, EasyPermissions.PermissionCallbacks {

    private EglBase.Context eglBaseContext;
    private PeerConnectionFactory peerConnectionFactory;
    private SurfaceViewRenderer localView;
    private MediaStream mediaStream;
    private List<PeerConnection.IceServer> iceServers;

    private HashMap<String, PeerConnection> peerConnectionMap;
    private SurfaceViewRenderer[] remoteViews;
    private int remoteViewsIndex = 0;
    private String selfSocketId;
    private String roomId;

    private static final String[] CAMERA_AND_AUDIO = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int RC_PERMS = 100;
    private static final int RC_CAMERA_PERM = 101;
    private static final int RC_AUDIO_PERM = 102;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        if (!hasPermissions()) {
            requestPermissions();
        } else {
            initWebRtc();
        }
    }

    @AfterPermissionGranted(RC_PERMS)
    private void initWebRtc() {
        peerConnectionMap = new HashMap<>();
        iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder(CommonConfig.ICE_SERVER).createIceServer());

        eglBaseContext = EglBase.create().getEglBaseContext();

        // create PeerConnectionFactory
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions());
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory defaultVideoEncoderFactory =
                new DefaultVideoEncoderFactory(eglBaseContext, true, true);
        DefaultVideoDecoderFactory defaultVideoDecoderFactory =
                new DefaultVideoDecoderFactory(eglBaseContext);
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory();

        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        // create VideoCapturer
        VideoCapturer videoCapturer = createCameraCapturer(true);
        VideoSource videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 640, 30);

        localView = findViewById(R.id.localView);
        localView.setMirror(true);
        localView.init(eglBaseContext, null);

        VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource);
        videoTrack.addSink(localView);


        remoteViews = new SurfaceViewRenderer[]{
                findViewById(R.id.remoteView),
                findViewById(R.id.remoteView2),
                findViewById(R.id.remoteView3)
        };
        for (SurfaceViewRenderer remoteView : remoteViews) {
            remoteView.setMirror(false);
            remoteView.init(eglBaseContext, null);
        }

        AudioSource audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource);


        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream");
        mediaStream.addTrack(videoTrack);
        mediaStream.addTrack(audioTrack);

        SignalingClient.get().init(this);
    }

    private void requestPermissions() {
        EasyPermissions.requestPermissions(
                this,
                "使用前需要您授权开启摄像头和麦克风",
                RC_PERMS,
                CAMERA_AND_AUDIO);
    }


    private synchronized PeerConnection getOrCreatePeerConnection(String socketId) {
        PeerConnection peerConnection = peerConnectionMap.get(socketId);
        if (peerConnection != null) {
            return peerConnection;
        }
        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnectionAdapter("PC:" + socketId) {
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                super.onIceCandidate(iceCandidate);
                Log.w("#####", "onIceCandidate，sdp" + iceCandidate.sdp);
                SignalingClient.get().sendIceCandidate(iceCandidate, socketId);
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.w("#####", "[onAddStream]");
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                runOnUiThread(() -> {
                    remoteViews[remoteViewsIndex].setTag(socketId);
                    remoteVideoTrack.addSink(remoteViews[remoteViewsIndex++]);
                });
            }


            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                super.onIceGatheringChange(iceGatheringState);
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.w("#####", "[onConnectionChange],newState =" + newState);
                for (SurfaceViewRenderer currentView : remoteViews) {
                    if (currentView.getTag() == socketId) {
                        if (currentView != null) {
                            runOnUiThread(() -> {
                                if (newState == PeerConnection.PeerConnectionState.CLOSED) {
                                    if (remoteViewsIndex-- < 0) {
                                        remoteViewsIndex = 0;
                                    }
                                    currentView.clearImage();
                                } else if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                                    currentView.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    }
                }


            }
        });
        peerConnection.addStream(mediaStream);
        peerConnectionMap.put(socketId, peerConnection);
        return peerConnection;
    }

    @Override
    public void onCreateRoom(String roomId, String socketId) {
        Log.d("#########", "[onCreateRoom], 房间创建成功，roomId =" + roomId + ", 您的socketId = " + socketId);
        this.selfSocketId = socketId;
        this.roomId = roomId;
    }

    @Override
    public void onSelfJoined(String roomId, String socketId) {
        //针对新加入者，不是房间创建者，房间创建者回调onCreateRoom
        Log.d("#########", "[onSelfJoined], 您已经加入房间，房间roomId =" + roomId + ", 您的socketId = " + socketId);
        this.selfSocketId = socketId;
        this.roomId = roomId;
    }

    @Override
    public void onPeerJoined(String roomId, String socketId) {
        this.roomId = roomId;
        Log.d("#########", "[onPeerJoined],有新加入者，socketId=" + socketId);
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.createOffer(new SdpAdapter("createOfferSdp:" + socketId) {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                super.onCreateSuccess(sessionDescription);
                peerConnection.setLocalDescription(new SdpAdapter("setLocalSdp:" + socketId), sessionDescription);
                SignalingClient.get().sendSessionDescription(sessionDescription, socketId);
            }
        }, new MediaConstraints());
    }

    @Override
    public void onOfferReceived(JSONObject data) {
        Log.d("#########", "[onOfferReceived]");
        runOnUiThread(() -> {
            final String socketId = data.optString("from");
            PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
            peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp:" + socketId),
                    new SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp")));
            peerConnection.createAnswer(new SdpAdapter("localAnswerSdp") {
                @Override
                public void onCreateSuccess(SessionDescription sdp) {
                    super.onCreateSuccess(sdp);
                    peerConnectionMap.get(socketId).setLocalDescription(new SdpAdapter("setLocalSdp:" + socketId), sdp);
                    SignalingClient.get().sendSessionDescription(sdp, socketId);
                }
            }, new MediaConstraints());

        });
    }

    @Override
    public void onAnswerReceived(JSONObject data) {

        Log.d("#########", "[onAnswerReceived]" + data);
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.setRemoteDescription(new SdpAdapter("setRemoteSdp:" + socketId),
                new SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp")));
    }

    @Override
    public void onIceCandidateReceived(JSONObject data) {
        Log.d("#########", "onIceCandidateReceived=" + data);
        String socketId = data.optString("from");
        PeerConnection peerConnection = getOrCreatePeerConnection(socketId);
        peerConnection.addIceCandidate(new IceCandidate(
                data.optString("id"),
                data.optInt("label"),
                data.optString("candidate")
        ));
    }

    @Override
    public void onPeerLeave(String roomId, String socketId) {
        Log.e("#####[onPeerLeave]", "有人离开房间 " + roomId + ",离去者是 " + socketId);
        PeerConnection peerConnection = peerConnectionMap.get(socketId);
        if (peerConnection != null) {
            peerConnection.close();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SignalingClient.get().leave(roomId);
    }

    private VideoCapturer createCameraCapturer(boolean isFront) {
        // Have permission, do the thing!
        Camera1Enumerator enumerator = new Camera1Enumerator(false);
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        for (String deviceName : deviceNames) {
            if (isFront ? enumerator.isFrontFacing(deviceName) : enumerator.isBackFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private boolean hasCameraPermission() {
        return EasyPermissions.hasPermissions(this, Manifest.permission.CAMERA);
    }

    private boolean hasAudioPermission() {
        return EasyPermissions.hasPermissions(this, Manifest.permission.RECORD_AUDIO);
    }

    private boolean hasPermissions() {
        return EasyPermissions.hasPermissions(this, CAMERA_AND_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        for (String p : perms) {
            Log.d("Permissions", "onPermissionsGranted, p =" + p);
        }
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        for (String p : perms) {
            Log.d("#####Permissions", "onPermissionsGranted, p =" + p);
        }
        if (perms.size() > 0) {
            finish();
        }
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            new AppSettingsDialog.Builder(this).build().show();
        }
    }

}
