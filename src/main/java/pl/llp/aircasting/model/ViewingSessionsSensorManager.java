package pl.llp.aircasting.model;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import pl.llp.aircasting.event.session.ViewSessionEvent;
import pl.llp.aircasting.model.internal.SensorName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newConcurrentMap;
import static com.google.common.collect.Maps.newHashMap;

/**
 * Created by radek on 10/10/17.
 */
@Singleton
public class ViewingSessionsSensorManager {
    @Inject EventBus eventBus;

    private volatile Map<Long, Map<SensorName, Sensor>> viewingSessionsSensors = newConcurrentMap();

    @Inject
    public void init() {
        eventBus.register(this);
    }

    public Map<Long, Map<SensorName, Sensor>> getAllViewingSensors() {
        return viewingSessionsSensors;
    }

    public Sensor getSensorByName(String sensorName, long sessionId) {
        return viewingSessionsSensors.get(sessionId).get(SensorName.from(sensorName));
    }

    @Subscribe
    public void onEvent(ViewSessionEvent event) {
        Session session = event.getSession();
        Map<SensorName, Sensor> sessionSensors = newHashMap();

        for (MeasurementStream stream : session.getMeasurementStreams()) {
            if (stream.isMarkedForRemoval()) {
                continue;
            }

            Sensor sensor = new Sensor(stream);
            sessionSensors.put(SensorName.from(stream.getSensorName()), sensor);
        }

        viewingSessionsSensors.put(event.getSession().getId(), sessionSensors);
    }

    public List<Sensor> getSensorsList(long sessionId) {
        ArrayList<Sensor> result = newArrayList();
        result.addAll(viewingSessionsSensors.get(sessionId).values());
        return result;
    }
}
