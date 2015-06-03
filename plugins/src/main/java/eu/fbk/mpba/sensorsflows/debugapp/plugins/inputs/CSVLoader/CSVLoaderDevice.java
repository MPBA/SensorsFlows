package eu.fbk.mpba.sensorsflows.debugapp.plugins.inputs.CSVLoader;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import eu.fbk.mpba.sensorsflows.DevicePlugin;
import eu.fbk.mpba.sensorsflows.SensorComponent;

public class CSVLoaderDevice implements DevicePlugin<Long, double[]>
{
    private List<SensorComponent<Long, double[]>> _sensors;
    private String name;

    public CSVLoaderDevice(Context context, String name) {
        this.name = name;
        _sensors = new ArrayList<>();
        //TODO Dee) devo leggere solo un file o multiple files con un sensor? solo uno ha senso!
        //TODO Dee) nome del file, lo prendo da una cartella??? chiedere a bat
        _sensors.add(new CSVLoaderSensor(";", "nomeFILEhahaNONsoBOHperche'COSI'vabe'COSAfaiVIENIqua'MAperche'CHIloSA'qualunqueCOSAfaiSIAMOsempreNEIguai", this));
    }

    @Override public void inputPluginInitialize()
    {
        // Avvio il sensore quando schiacci il bottone
        _sensors.get(0).switchOnAsync();
    }

    @Override public void inputPluginFinalize()
    {
        // Finalizzo
    }

    @Override public Iterable<SensorComponent<Long, double[]>> getSensors()
    {
        return _sensors;
    }
}
