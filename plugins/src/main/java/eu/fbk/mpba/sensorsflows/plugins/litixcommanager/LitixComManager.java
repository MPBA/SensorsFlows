package eu.fbk.mpba.sensorsflows.plugins.litixcommanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import eu.fbk.mpba.litixcom.core.Track;
import eu.fbk.mpba.litixcom.core.eccezioni.ConnectionException;
import eu.fbk.mpba.litixcom.core.eccezioni.DeadDatabaseServerException;
import eu.fbk.mpba.litixcom.core.eccezioni.LoginException;
import eu.fbk.mpba.litixcom.core.mgrs.auth.Certificati;
import eu.fbk.mpba.litixcom.core.mgrs.auth.Credenziali;
import eu.fbk.mpba.litixcom.core.mgrs.messages.Sessione;

public class LitixComManager {
    private final SQLiteDatabase buffer;
    private final Thread th;
    protected LitixComWrapper com;

    private Track track;

    public int newTrack(Sessione s) throws ConnectionException, LoginException, DeadDatabaseServerException {
        this.track = com.newTrack(s);
        return track.getTrackId();
    }

    final Queue<Long> queue = new ArrayDeque<>();
    final Semaphore queueSemaphore = new Semaphore(0);
    byte[] lastSplit = null;

    public synchronized void notifySplit(Long id) {
        if (!commitPending) {
            synchronized (queue) {
                queue.add(id);
            }
            queueSemaphore.release();
            Log.v("notifySplit", "added " + id);
        }
        else
            throw new NullPointerException("Track already committed.");
    }

    private byte[] loadSplit() {
        synchronized (queue) {
            if (lastSplit == null) {
                Cursor x = buffer.query("split", new String[]{"data"}, "first_ts == " + queue.peek(), null, null, null, null);
                if (x.moveToLast()) {
                    lastSplit = x.getBlob(0);
                } else {
                    Log.e("DBReader", "Split ID not found in database!");
                }
                x.close();
            }
        }
        return lastSplit;
    }

    private void processSplitsQueue() {
        Log.d("Man", Thread.currentThread().getName());
        try {
            while (true) {
                try {
                    Log.d("Man", "Waiting for permits:" + queueSemaphore.availablePermits());
                    queueSemaphore.acquire();

                    if (readyToClose()) {
                        Log.d("Man", "Committing");
                        track.commit();
                        Log.d("Man", "Committed");
                        break;
                    }
                    else {
                        Log.d("Man", "Pushing");
                        track.put(loadSplit());

                        synchronized (queue) {
                            queue.remove();
                        }
                        // Uploaded, cleaning cache
                        lastSplit = null;
                        Log.d("Man", "Pushed");
                    }
                } catch (ConnectionException | LoginException e) {
                    Log.d("Man", "Push failed, releasing once, sleeping 13375ms", e);
                    queueSemaphore.release();
                    Thread.sleep(13375);
                } catch (DeadDatabaseServerException e) {
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException ignored) {
            Log.d("Man", Thread.currentThread().getName() + " interrupted, exiting processSplitsQueue");
        }
    }

    public LitixComManager(final Activity activity, InetSocketAddress address, SQLiteDatabase database, Certificati c) {
        buffer = database;
        com = new LitixComWrapper(address, new Credenziali() {

            final AtomicReference<String> username = new AtomicReference<>(null);

            public String getUsername() {
                final Semaphore semaphore = new Semaphore(0);
                InputDialog.makeText(activity, new InputDialog.ResultCallback<String>() {
                    @Override
                    public void ok(String result) {
                        username.set(result);
                        semaphore.release();
                    }

                    @Override
                    public void cancel() {
                        semaphore.release();
                    }
                }, "Physiolitix - Login", "Master's username").show();
                try {
                    semaphore.acquire();
                    return username.get();
                } catch (InterruptedException e) {
                    return null;
                }
            }

            public String getPassword() {
                final Semaphore semaphore = new Semaphore(0);
                final AtomicReference<String> password = new AtomicReference<>(null);
                InputDialog.makePassword(activity, new InputDialog.ResultCallback<String>() {
                            @Override
                            public void ok(String result) {
                                password.set(result);
                                semaphore.release();
                            }

                            @Override
                            public void cancel() {
                                semaphore.release();
                            }
                        },
                        "Physiolitix - Login", String.format("Password for %s", username.get())).show();
                try {
                    semaphore.acquire();
                    return password.get();
                } catch (InterruptedException e) {
                    return null;
                }
            }

            public String getDeviceId() {
                return getXDID(activity);
            }

            @Override
            public void setToken(String token) {
                File d = activity.getDir(LitixComManager.class.getSimpleName(), Context.MODE_PRIVATE);
                File t = new File(d, "halo_memory");
                try {
                    FileOutputStream o = new FileOutputStream(t);
                    o.write(token.getBytes());
                } catch (FileNotFoundException e) {
                    Log.e(LitixComManager.class.getSimpleName(), "Cannot create a private file.");
                } catch (IOException e) {
                    Log.e(LitixComManager.class.getSimpleName(), "Cannot write a private file.");
                }
            }

            @Override
            public String getToken() {
                File d = activity.getDir(LitixComManager.class.getSimpleName(), Context.MODE_PRIVATE);
                File t = new File(d, "halo_memory");
                String token = null;
                try {
                    FileInputStream o = new FileInputStream(t);
                    byte[] buf = new byte[512];
                    if (o.read(buf, 0, 512) > 0)
                        token = new String(buf);
                } catch (FileNotFoundException ignore) {
                } catch (IOException e) {
                    Log.e(LitixComManager.class.getSimpleName(), "Cannot read a private file.");
                }
                return token;
            }

            @Override
            public Triple getUsernamePasswordDeviceId() {
                String name, surname, address;
                name = getUsername();
                if (name != null) {
                    surname = getPassword();
                    if (surname != null) {
                        address = getDeviceId();
                        return new Triple(name, surname, address);
                    }
                }
                return new Triple(null, null, null); // FIXME return null
            }

            @Override
            public boolean onErrorGetRetry(final Exception e) {
                final AtomicReference<Boolean> r = new AtomicReference<>(false);
                final Semaphore semaphore = new Semaphore(0);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        new AlertDialog.Builder(activity)
                                .setTitle("Phisiolitix - Login")
                                .setMessage(e == null ?
                                        "Login error, wrong name or password." :
                                        String.format("Login error.\n(Error: %s)", e.getMessage()))
                                .setPositiveButton("Retry", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        r.set(true);
                                        semaphore.release();
                                    }
                                })
                                .setNegativeButton("Retry", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        semaphore.release();
                                    }
                                }).show();

                    }
                });
                try {
                    semaphore.acquire();
                } catch (InterruptedException e1) {
                    return false;
                }
                return r.get();
            }
        }, c);
        th = new Thread(new Runnable() {
            @Override
            public void run() {
                processSplitsQueue();
            }
        }, "LitixCom uploader");
        th.setDaemon(true);
        th.start();
    }

    public List<Sessione> getAuthorizedSessions() throws ConnectionException, LoginException {
        return com.getSessionsList();
    }

    public int queuedUploads() {
        synchronized (queue) {
            return queue.size();
        }
    }

    private boolean commitPending = false;

    public void enqueueCommit() {
        commitPending = true;
        queueSemaphore.release();
    }

    public synchronized boolean readyToClose() {
        return queuedUploads() == 0 && commitPending;
    }

    public boolean close() {
        if (readyToClose()) {
            th.interrupt();
            buffer.close();
            return true;
        } else
            return false;
    }

    @NonNull
    private String getXDID(Context context) {
        String a = ((TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE)).getDeviceId();
        if (a == null)
            a = "a-" + Settings.Secure.ANDROID_ID;
        else
            a = "u-" + a;
        return a;
    }
}