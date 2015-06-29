package eu.fbk.mpba.sensorsflows.testapp;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

import eu.fbk.mpba.sensorsflows.AutoLinkMode;
import eu.fbk.mpba.sensorsflows.FlowsMan;
import eu.fbk.mpba.sensorsflows.OutputPlugin;
import eu.fbk.mpba.sensorsflows.SensorComponent;
import eu.fbk.mpba.sensorsflows.base.EngineStatus;
import eu.fbk.mpba.sensorsflows.base.ISensor;
import eu.fbk.mpba.sensorsflows.base.SensorDataEntry;
import eu.fbk.mpba.sensorsflows.base.SensorEventEntry;
import eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.CSVLoader.CSVLoaderDevice;
import eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.EXLs3.EXLs3Device;
import eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.EXLs3.EXLs3ToFile;
import eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.android.SmartphoneDevice;
import eu.fbk.mpba.sensorsflows.plugins.plugins.inputs.empatica.EmpaticaDevice;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.CsvDataSaver;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.CsvOutput;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.ProtobufferOutput;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.SQLiteOutput;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.SensorsProtobuffer;
import eu.fbk.mpba.sensorsflows.plugins.plugins.outputs.TCPServerOutput;


public class MainActivity extends Activity {

    FlowsMan<Long, double[]> m = new FlowsMan<>();
    SmartphoneDevice smartphoneDevice;
    LinearLayout selection;

    void addPluginChoice(boolean input, String name, Runnable initialization) {
        LinearLayout sel = (LinearLayout) findViewById(R.id.pluginSelection);
        CheckBox x = new CheckBox(this);
        x.setChecked(false);
        x.setTag(initialization);
        x.setText((input ? "(in)" : "(out)") + name);
        sel.addView(x);
    }

    private void runSelectedInitializations() {
        for (int i = 0; i < selection.getChildCount(); i++) {
            CheckBox x =  (CheckBox)selection.getChildAt(i);
            if (x.isChecked())
                ((Runnable) x.getTag()).run();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        selection = (LinearLayout) findViewById(R.id.pluginSelection);
        final Context _this = this;
        addPluginChoice(true, "EXLs3", new Runnable() {
            @Override
            public void run() {
                m.addDevice(new EXLs3Device(BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:80:e1:b3:4e:B3".toUpperCase()), BluetoothAdapter.getDefaultAdapter(), "EXL_174", 0, 300));
            }
        });
        addPluginChoice(true, "Smartphone", new Runnable() {
            @Override
            public void run() {
                m.addDevice(smartphoneDevice = new SmartphoneDevice(_this, "Smartphone"));
            }
        });
        addPluginChoice(true, "Empatica", new Runnable() {
            @Override
            public void run() {
                m.addDevice(new EmpaticaDevice("e250d5fdb4644d7bbd8cbbcd4acfb860", _this, "", new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(_this, "ENABLE BT in 5 sec!!!", Toast.LENGTH_LONG).show();
                        try {
                            Thread.sleep(5000, 0);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }));
            }
        });


        /**
         * Prova CSVLoader
         * */
        addPluginChoice(true, "CSVLoader", new Runnable() {
            @Override
            public void run() {

                //Creo il device
                CSVLoaderDevice cl = new CSVLoaderDevice("nonsochenomedargli0123456789");
                cl.setAsyncActionOnFinish(new Runnable(){public void run()
                {
                        ((Activity)_this).runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(((Activity)_this), "FINITOOOOO :D", Toast.LENGTH_LONG).show();
                        }
                    });
                }});


                //Prendo la scala timestamp per alcuni files.
                HashMap<String, Long> scale = new HashMap<>();
                BufferedReader br = null;
                try
                {
                    br = new BufferedReader(new FileReader(Environment.getExternalStorageDirectory().getPath() + "/eu.fbk.mpba.sensorsflows/inputCSVLoader/input_config.txt"));
                    String line;
                    while ((line = br.readLine()) != null)
                    {
                        if(line.replaceAll("\\s","").charAt(0) != '#')
                        {
                            String[] parts = line.split(";");
                            scale.put(parts[0], Long.parseLong(parts[1]));
                        }
                    }
                }
                catch (Exception e){Log.i("CSVL", e.getMessage());}
                finally {
                    try{br.close();}
                    catch (Exception e){Log.i("CSVL", e.getMessage());}
                }


                //Carico i files dalla cartella di input
                final File folder = new File(Environment.getExternalStorageDirectory().getPath() + "/eu.fbk.mpba.sensorsflows/inputCSVLoader");
                for (final File fileEntry : folder.listFiles())
                {
                    if (fileEntry.isFile() && !fileEntry.getName().equals("input_config.txt"))
                    {
                        long tsScale = 1;
                        Long tmp = scale.get(fileEntry.getName());
                        if(tmp != null)
                            tsScale = tmp;

                        try{cl.addFile(new InputStreamReader(new FileInputStream(fileEntry)), ";", "\n", tsScale, fileEntry.getName());}
                        catch (Exception e){Log.i("CSVL", e.getMessage());}
                    }
                }

                m.addDevice(cl);
            }
        });

        addPluginChoice(false, "CSV", new Runnable() {
            @Override
            public void run() {
                m.addOutput(new CsvOutput("CSV",
                        Environment.getExternalStorageDirectory().getPath()
                                + "/eu.fbk.mpba.sensorsflows/"));
            }
        });
        addPluginChoice(false, "SQLite", new Runnable() {
            @Override
            public void run() {
                m.addOutput(new SQLiteOutput("DB",
                        Environment.getExternalStorageDirectory().getPath()
                                + "/eu.fbk.mpba.sensorsflows/"));
            }
        });
        addPluginChoice(false, "Protobuffer", new Runnable() {
            @Override
            public void run() {
                Hashtable<Class, SensorsProtobuffer.SensorInfo.TYPESENSOR> types = new Hashtable<>();
                //        types.put(GpsSensor.class, SkiloProtobuffer.SensorInfo.TYPESENSOR.GPS);
                //        types.put(AccelerometerSensor.class, SkiloProtobuffer.SensorInfo.TYPESENSOR.ACC);
                //        types.put(TextEventsSensor.class, SkiloProtobuffer.SensorInfo.TYPESENSOR.MARKER);
                types.put(EXLs3Device.EXLAccelerometer.class, SensorsProtobuffer.SensorInfo.TYPESENSOR.ACC);
                types.put(EXLs3Device.EXLGyroscope.class, SensorsProtobuffer.SensorInfo.TYPESENSOR.GYRO);
                types.put(EXLs3Device.EXLMagnetometer.class, SensorsProtobuffer.SensorInfo.TYPESENSOR.MAGNE);
                types.put(EXLs3Device.EXLQuaternion.class, SensorsProtobuffer.SensorInfo.TYPESENSOR.QUAT);
                types.put(EXLs3Device.EXLBattery.class, SensorsProtobuffer.SensorInfo.TYPESENSOR.BATTERY);

                m.addOutput(new ProtobufferOutput("Protobuf", new File(
                        Environment.getExternalStorageDirectory().getPath()
                                + "/eu.fbk.mpba.sensorsflows/"), 1000, UUID.randomUUID().toString(), types));
            }
        });
        addPluginChoice(false, "TCPServer", new Runnable() {
            @Override
            public void run() {
                try {
                    TCPServerOutput x = new TCPServerOutput(2000);
                    m.addOutput(x);
                } catch (IOException e) {
                    Toast.makeText(_this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        addPluginChoice(false, "User", new Runnable() {
            @Override
            public void run() {
                m.addOutput(new OutputPlugin<Long, double[]>() {
                    @Override
                    public void outputPluginInitialize(Object sessionTag, List<ISensor> streamingSensors) {
                        // Executed on the last part of the start
                        // sessionTag       : the name of the session (.toString())
                        // streamingSensors : List with every sensor of the session
                        Log.v("UD PLUGIN", String.format("INIT %s - %d sensors", sessionTag, streamingSensors.size()));
                    }

                    @Override
                    public void outputPluginFinalize() {
                        // When close is called
                        Log.v("UD PLUGIN", "FINAL");
                    }

                    @Override
                    public void newSensorEvent(SensorEventEntry<Long> event) {
                        // Events like I said
                        // event
                        //     .sensor      : sender sensor, useful with the instanceof to filter the events
                        //     .timestamp   : event's
                        //     .code        : numeric or flags, see the sensor's code
                        //     .message     : descriptive string
                        Log.v("UD PLUGIN", String.format("%d %s: %d: %s", event.timestamp, event.sensor, event.code, event.message));
                    }

                    @Override
                    public void newSensorData(SensorDataEntry<Long, double[]> data) {
                        // Data linke I said
                        // data
                        //     .sensor      : sender sensor, useful with the instanceof to filter the events
                        //     .timestamp   : data's
                        //     .value       : value of double[] type, see the sensor's code
                    }
                });
            }
        });
    }

    @SuppressWarnings("SpellCheckingInspection")
    public void onMStart(View v) {
        if (m.getStatus() == EngineStatus.STANDBY) {
            runSelectedInitializations();
            m.setAutoLinkMode(AutoLinkMode.PRODUCT);
            m.start(CsvDataSaver.getHumanDateTimeString());
        }
        else
            Toast.makeText(this, "m.close() before", Toast.LENGTH_SHORT).show();
    }

    public void onMClose(View v) {
        m.close();
        m = new FlowsMan<>();
    }

    EXLs3ToFile d;

    public void onBTDStart(View v) {
        if (d == null) {
            d = new EXLs3ToFile(null, BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:80:E1:B3:4E:E0".toUpperCase()), BluetoothAdapter.getDefaultAdapter());
        }
        else {
            d.start();
        }
    }

    public void onBTDClose(View v) {
        if (d != null)
            d.stop();
    }

    public void onAddText(View v) {
        smartphoneDevice.addNoteNow(((TextView) findViewById(R.id.editText)).getText().toString());
        ((TextView) findViewById(R.id.editText)).setText("");
    }
}
