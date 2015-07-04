package eu.fbk.mpba.sensorsflows.base;

import java.util.List;

import eu.fbk.mpba.sensorsflows.SensorImpl;

/**
 * Main interface for the data management.
 * TimeT and ValueT must be the same for the whole library.
 */
public interface IOutput<TimeT, ValueT> extends ISensorDataCallback<ISensor, TimeT, ValueT> {

    public void initialize();

    public void setLinkedSensors(List<SensorImpl> linkedSensors);

    public OutputStatus getState();

    public void finalizeOutput();
}