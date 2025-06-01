package org.example;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.*;

public class Broker_Custom extends DatacenterBroker {

    private static final CloudSimTags CUSTOM_EVENT_TAG = CustomCloudSimTags.CUSTOM_EVENT_TAG;
    private static final CloudSimTags SCHEDULING_RESPONSE_EVENT = CustomCloudSimTags.SCHEDULING_RESPONSE_EVENT;
    private static final CloudSimTags POLL_TAG = CustomCloudSimTags.POLL_TAG;
    private static final CloudSimTags INIT_BROKER = CustomCloudSimTags.INIT_BROKER;
    private static final String CONTROL_PLANE_URL = "http://localhost:8080";

    private final HttpClient httpClient;
    private final Map<Integer, Cloudlet> cloudletMap = new HashMap<>();
    private final List<Cloudlet> cloudletsToSubmit = new ArrayList<>();

    public Broker_Custom(String name) throws Exception {
        super(name);
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void startEntity() {
        super.startEntity();
        postNodesToScheduler();
        cloudletsToSubmit.addAll(getCloudletList());
        getCloudletList().clear();
        schedule(getId(), 1.0, INIT_BROKER);
    }

    private void postNodesToScheduler() {
        List<GuestEntity> containers = getGuestList();
        StringBuilder nodesJson = new StringBuilder("[");

        for (int i = 0; i < containers.size(); i++) {
            GuestEntity container = containers.get(i);
            nodesJson.append(String.format("{\"id\":%d}", container.getId()));
            if (i < containers.size() - 1) nodesJson.append(",");
        }
        nodesJson.append("]");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/nodes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(nodesJson.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            System.out.println("[REAL] Posted nodes to scheduler: " + response.body());
        } catch (Exception ex) {
            System.err.println("[ERROR] Failed to POST /nodes: " + ex.getMessage());
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag().equals(INIT_BROKER)) {
            if (getDatacenterIdsList().isEmpty()) {
                schedule(getId(), 0.5, INIT_BROKER);
            } else {
                for (Cloudlet cloudlet : cloudletsToSubmit) {
                    schedule(getId(), 0.1, CUSTOM_EVENT_TAG, cloudlet);
                }
                cloudletsToSubmit.clear();
            }

        } else if (ev.getTag().equals(CUSTOM_EVENT_TAG)) {
            Cloudlet cloudlet = (Cloudlet) ev.getData();
            cloudletMap.put(cloudlet.getCloudletId(), cloudlet);

            String podJson = String.format("{\"id\":%d}", cloudlet.getCloudletId());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/pods"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(podJson))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                System.out.println("[REAL] Submitted pod: " + cloudlet.getCloudletId());
                schedule(getId(), 0.5, POLL_TAG, cloudlet.getCloudletId());
            } catch (Exception ex) {
                System.err.println("[ERROR] Failed to POST /pods: " + ex.getMessage());
            }

        } else if (ev.getTag().equals(POLL_TAG)) {
            int podId = (int) ev.getData();
            try {
                HttpRequest statusRequest = HttpRequest.newBuilder()
                        .uri(URI.create(CONTROL_PLANE_URL + "/pods/" + podId + "/status"))
                        .build();

                HttpResponse<String> response = httpClient.send(statusRequest, BodyHandlers.ofString());
                System.out.println("[DEBUG] Pod status response: " + response.body());
                String body = response.body().replaceAll("[^0-9]", "");
                int containerId = Integer.parseInt(body);
                if (containerId >= 0) {
                    schedule(getId(), 0, SCHEDULING_RESPONSE_EVENT, Map.entry(podId, containerId));
                } else {
                    schedule(getId(), 0.5, POLL_TAG, podId);
                }
            } catch (Exception ex) {
                System.err.println("[ERROR] Failed to GET pod status: " + ex.getMessage());
                schedule(getId(), 0.5, POLL_TAG, podId);
            }

        } else if (ev.getTag().equals(SCHEDULING_RESPONSE_EVENT)) {
            Map.Entry<Integer, Integer> result = (Map.Entry<Integer, Integer>) ev.getData();
            int podId = result.getKey();
            int containerId = result.getValue();

            Cloudlet cloudlet = cloudletMap.get(podId);
            cloudlet.setGuestId(containerId);

            if (!getDatacenterIdsList().isEmpty()) {
                sendNow(getDatacenterIdsList().get(0), CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
                System.out.printf("[SIM] Cloudlet %d scheduled to container %d\n", podId, containerId);
            } else {
                System.err.println("[ERROR] No datacenter found to submit cloudlet " + podId);
            }
        }
    }
}

enum CustomCloudSimTags implements CloudSimTags {
    CUSTOM_EVENT_TAG,
    SCHEDULING_RESPONSE_EVENT,
    POLL_TAG,
    INIT_BROKER
}
