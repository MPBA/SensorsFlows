package eu.fbk.mpba.sensorsflows.plugins.inputs.comftech;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.UUID;

public abstract class CozyBabyReceiver {

    // Debug
    private static final String TAG = CozyBabyReceiver.class.getSimpleName();

    // UUID for rfcomm connection
    @SuppressWarnings("SpellCheckingInspection")
    private static final UUID UUID_INSECURE =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    // Member fields
    private BTSrvState mState = BTSrvState.IDLE;
    private BluetoothSocket mSocket;
    private InputStream mInput;
    private OutputStream mOutput;
    private Thread mDispatcher;
    protected final BluetoothAdapter mAdapter;
    protected final StatusDelegate mStatusDelegate;
    protected final BluetoothDevice mDevice;
    private boolean dispatch = true;
    public long antiIgnore = 0; // On EXL was 100
    private boolean hardCheckSum = false;
    private int checkSumErrors = 10;
    private boolean useCheckSum = true;

    public CozyBabyReceiver(StatusDelegate statusDelegate, BluetoothDevice device, BluetoothAdapter adapter) {
        mStatusDelegate = statusDelegate;
        mDevice = device;
        mAdapter = adapter;
        mDispatcher = new Thread(new Runnable() {
            @Override
            public void run() {
                dispatch();
            }
        });
    }

    // Operation

    private boolean startPending = false;

    protected void connect() { // TODO 8 Using only the insecure mode
        dispatch = true;
        if (!mAdapter.isEnabled()) {
            try {
                mAdapter.enable();
            } catch (Exception e) {
                Log.e(TAG, "Can't enable BT! Getting over.", e);
            }
        }
        setState(BTSrvState.CONNECTING);
        if (mStatusDelegate != null)
            mStatusDelegate.connecting(this, mDevice, false);
        if (innerTryConnect()) {     // Acts as a reset
            // Connection Established
            setState(BTSrvState.CONNECTED);
            if (mStatusDelegate != null)
                mStatusDelegate.connected(this, mDevice.getAddress() + "-" + mDevice.getName());
            // Get io streams
            try {
                Log.v(TAG, "Getting I/O streams");
                mInput = mSocket.getInputStream();
                mOutput = mSocket.getOutputStream();
                Log.v(TAG, "Got I/O streams");

                mDispatcher = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        dispatch();
                    }
                });
                Log.v(TAG, "Starting asynch dispatcher");
                mDispatcher.start();
                Log.v(TAG, "Stort");

            } catch (IOException e) {
                Log.e(TAG, "Can't get I/O streams", e);
                // Connection Failed
                setState(BTSrvState.DISCONNECTED);
                if (mStatusDelegate != null)
                    mStatusDelegate.disconnected(this, StatusDelegate.DisconnectionCause.IO_STREAMS_ERROR);
                close();
            }
        }
        else {
            // Connection Failed
            setState(BTSrvState.DISCONNECTED);
            if (mStatusDelegate != null)
                mStatusDelegate.disconnected(this, mSocket == null
                        ? StatusDelegate.DisconnectionCause.IO_SOCKET_ERROR
                        : StatusDelegate.DisconnectionCause.DEVICE_NOT_FOUND);
            close();
        }
    }

    public void command(byte[] c) {
        if (mOutput != null && mSocket != null && mSocket.isConnected()) {
            try {
                mOutput.write(c);
                Log.v(TAG, "Query sent");
            } catch (IOException e) {
                Log.e(TAG, "Query error", e);
            }
        }
        else {
            startPending = true;
            Log.e(TAG, "Query no connection");
        }
    }

    protected void close() {
        Log.d(TAG, "close");
        command(Commands.stopStreaming);
        setState(BTSrvState.DISCONNECTED); // Need this to handle the IOException
        dispatch = false;
        try {
            if(mInput != null) {
                mInput.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if(mOutput != null) {
                mOutput.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if(mSocket != null) {
                mSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        mOutput = null;
        mSocket = null;
    }

    private void dispatch() {
        // In uno dei percorsi scarta bytes,
        // utilizzabile solo per controllare il packet counter
        Log.i(TAG, "Started dispatching...");
        int lostBytes = 0;
        try {
            int i = 0;
            int[] pack = new int[512 + 8];

            // Should solve the ignored start stream command issue (EXLs3 and maybe others).
            if (antiIgnore > 0)
                try {
                    Thread.sleep(antiIgnore);
                    Log.d(TAG, "Anti-ignore: of " + antiIgnore + "ms");
                } catch (InterruptedException e) {
                    Log.d(TAG, "Dispatch interrupted on anti-ignore sleep.");
                }
            else
                Log.d(TAG, "Anti-ignore: skipped");

            // Used continue to minimize the waste of bytes.
            // Used while true to use continue in a comfortable way.
            // Exit condition: (!dispatch || Thread.currentThread().isInterrupted()) with a break.
            while (true) {
                if (i > 0) {
                    lostBytes += i;     // The last cycle would have reset i if the packet had been accepted
                    Log.d(TAG, "Bytes lost: " + i + " total: " + lostBytes);
                    i = 0;
                }

                if (!dispatch) {
                    Log.d(TAG, "Dispatch thread end due to EOS or user action.");
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    Log.d(TAG, "Dispatch thread end due to a thread interruption.");
                    break;
                }

                // Start High (0)
                pack[i++] = mInput.read();
                if (pack[0] < 0)
                    dispatchEOS();
                if (pack[0] != 0xA0)
                    continue;
                // Start Low (1)
                pack[i++] = mInput.read();
                if (pack[1] < 0)
                    dispatchEOS();
                if (pack[1] != 0xA2)
                    continue;

                // Length High (2)
                pack[i++] = mInput.read();
                if (pack[2] < 0)
                    dispatchEOS();
                // Length Low (3)
                pack[i++] = mInput.read();
                if (pack[3] < 0)
                    dispatchEOS();

                // TODO check: assert: 15-bit values are big-endian (byte alto in prima poszione [cit.])
                int length = pack[2] << 8 + pack[3];

                // TODO check: assert length not equal but less than 512 (inferiore a 512 [cit.]) and not as in the image ("up to 2^10-1 (<1023)" [cit.] that is incoherent with the first predicate).
                if (length < 512) {
                    for (int j = 0; j < length; j++) {
                        if ((pack[i++] = mInput.read()) < 0) {
                            dispatchEOS();
                            break;
                        }
                    }
                    // If not EOS (-1)
                    if (dispatch) {

                        // Checksum High
                        int CH = i;
                        pack[i++] = mInput.read();
                        if (pack[CH] < 0)
                            dispatchEOS();
                        // Checksum Low
                        int CL = i;
                        pack[i++] = mInput.read();
                        if (pack[CL] < 0)
                            dispatchEOS();

                        // End High
                        int EH = i;
                        pack[i++] = mInput.read();
                        if (pack[EH] < 0)
                            dispatchEOS();
                        if (pack[EH] != 0xB0)
                            continue;
                        // End Low
                        int EL = i;
                        pack[i++] = mInput.read();
                        if (pack[EL] < 0)
                            dispatchEOS();
                        if (pack[EL] != 0xB3)
                            continue;


                        // Checksum Computation
                        // TODO check the actual algorithm pseudo code is the (1), assert (2) is equivalent
                        // (1)
                        //    Index = first
                        //    checkSum = 0
                        //    while index <msgLen
                        //        checkSum = checkSum + message[index]
                        //    checkSum = checkSum AND (215-1).
                        //        incrementindex
                        //
                        // (2)
                        //    Index = first
                        //    checkSum = 0
                        //    while index <msgLen
                        //        checkSum = checkSum + message[index]
                        //        checkSum = checkSum AND 0b11010110 // (215-1) == (214) == 0b11010110
                        //        incrementindex

                        int myCS = 0, rcCS = 0;
                        if (useCheckSum) {
                            // Index = first
                            int c = 0;
                            // checkSum = 0
                            myCS = 0;
                            // while index <msgLen
                            while (c < i) {
                                // checkSum = checkSum + message[index]
                                myCS += pack[c];
                                // checkSum = checkSum AND 0b11010110
                                myCS &= 0b11010110;
                                // incrementindex
                                c++;
                            }

                            rcCS = pack[CH] << 8 + pack[CL];
                        }

                        if (rcCS == myCS || !hardCheckSum) {
                            if (rcCS == myCS) {
                                if (checkSumErrors > 0)
                                    checkSumErrors--;
                            } else {
                                Log.d(TAG, "EDCPositive pack kept (my vs rc): " + Integer.toBinaryString(myCS) + " vs " + Integer.toBinaryString(rcCS));
                                if (checkSumErrors++ > 128)
                                    Log.i(TAG, "CheckSum check disabled (checkSumErrors - packets > 256).");
                            }
                            // This is a full kept packet
                            int[] result = new int[length];
                            System.arraycopy(pack, 4, result, 0, length);
                            received(result, length);
                            i = 0;
                        } else {
                            Log.d(TAG, "EDCPositive pack discarded (my vs rc): " + Integer.toBinaryString(myCS) + " vs " + Integer.toBinaryString(rcCS));
                            if (checkSumErrors++ > 16) {
                                hardCheckSum = false;
                                Log.d(TAG, "Hard checkSum check disabled (checkSumErrors - packets > 64).");
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "disconnection: " + e.getClass().getSimpleName());
            switch (e.getMessage()) {
                case "Operation Canceled":
                case "bt socket closed, read return: -1":
                    if (!dispatch) {
                        Log.d(TAG, "Legal BT socket disconnection");
                    } else {
                        Log.e(TAG, "Connection lost", e);
                        setState(BTSrvState.DISCONNECTED);
                        if (mStatusDelegate != null)
                            mStatusDelegate.disconnected(this, StatusDelegate.DisconnectionCause.CONNECTION_LOST);
                    }
                    break;
                case "Software caused connection abort":
                    Log.e(TAG, "Software caused connection abort", e);
                    break;
                default:
                    Log.e(TAG, "Unmanaged disconnection", e);
                    break;
            }
        }
    }

    private void dispatchEOS() {
        dispatch = false;
        Log.e(TAG, "End of the stream reached.");
    }

    private boolean innerTryConnect() {
        if (mDevice != null) {
            String devInfo = mDevice.getAddress() + "-" + mDevice.getName();
            Log.d(TAG, devInfo + ": try connect");
            mSocket = createSocket(mDevice);
            if (!mAdapter.cancelDiscovery())
                Log.d(TAG, devInfo + ": cancelDiscovery failed, getting over");
            try {
                mSocket.connect();
                Log.i(TAG, devInfo + ": connected");
                if (startPending) {
                    Log.d(TAG, devInfo + ": startPending true, starting streaming");
                    command(Commands.startStreaming);
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, devInfo + ": IOException, connect failure");
                switch (e.getMessage()) {
                    case "read failed, socket might closed or timeout, read ret: -1":
                    case "Bluetooth is off":
                    case "Device or resource busy":
                    case "Host is down":
                        Log.e(TAG, e.getMessage());
                        break;
                    default:
                        Log.e(TAG, "Unrecognized connect failure", e);
                        break;
                }
                return false;
            }
        }
        else
            return false;
    }

    // Status

    public BTSrvState getState() {
        return mState;
    }

    protected void setState(BTSrvState state) {
        Log.v(TAG, "+Status " + mState + " -> " + state);
        mState = state;
    }

    // Util

    private static BluetoothSocket createSocket(final BluetoothDevice device) {
        BluetoothSocket socket = null;
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            socket = (BluetoothSocket)m.invoke(device, 1);
        }
        catch (NoSuchMethodException ignore) {
            try {
                socket = device.createRfcommSocketToServiceRecord(UUID_INSECURE);
            } catch (IOException e) {
                Log.e(TAG, "IOException trying to create the socket", e);
            }
        }
        catch (Exception e) {
            Log.e(TAG, "Unable to create the socket", e);
        }
        return socket;
    }

    // To implement

    protected abstract void received(int[] buffer, int bytes);

    // Subclasses

    public interface StatusDelegate {

        int
                READY = 0,
                CONNECTING = 1,
                CONNECTED = 2,
                DISCONNECTED = 4;

        void connecting(CozyBabyReceiver sender, BluetoothDevice device, boolean secureMode);
        void connected(CozyBabyReceiver sender, String deviceName);
        void disconnected(CozyBabyReceiver sender, DisconnectionCause cause);

        enum DisconnectionCause {

            DEVICE_NOT_FOUND(8),
            IO_STREAMS_ERROR(16),
            IO_SOCKET_ERROR(24),
            CONNECTION_LOST(32),
            WRONG_PACKET_TYPE(40),
            OTHER(48);

            public final int flag;

            DisconnectionCause(int v) { flag = v; }
        }
    }

    public enum BTSrvState {
        IDLE,          // we're doing nothing
        CONNECTING,    // now initiating an outgoing connection
        CONNECTED,     // now connected to a remote device
        DISCONNECTED   // disconnected from device, error or
    }

    protected static class Commands { // TODO check: understand what to do
        public static byte[] startStreaming = new byte[] { };
        public static byte[] stopStreaming = new byte[] { };
    }
}