package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.lists.VmList;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSimTags;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Broker_Custom extends DatacenterBroker {

    private static final String CONTROL_PLANE_URL = "http://localhost:8080";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Map<Integer, Cloudlet> pendingCloudletsForScheduling;
    private boolean initialNodesSent = false;
    // Removed simulationTerminationInitiated flag as it's no longer needed for explicit termination calls.

    private int totalInitialCloudlets = 0;
    private final AtomicInteger finishedCloudletsCount = new AtomicInteger(0);

    public Broker_Custom(String name) throws Exception {
        super(name);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.pendingCloudletsForScheduling = new HashMap<>();
    }

    @Override
    public void startEntity() {
        super.startEntity();
        schedule(getId(), 0.0, CloudActionTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    @Override
    public void submitCloudletList(List<? extends Cloudlet> list) {
        super.submitCloudletList(list);
        this.totalInitialCloudlets = list.size();
    }

    @Override
    public void processEvent(SimEvent ev) {
        // No need for a termination check here anymore, as we rely on natural shutdown.
        // All events will be processed until CloudSim's queue is empty.

        if (ev.getTag() == CloudActionTags.RESOURCE_CHARACTERISTICS_REQUEST) {
            processResourceCharacteristicsRequest(ev);
        } else if (ev.getTag() == CloudActionTags.RESOURCE_CHARACTERISTICS) {
            processResourceCharacteristics(ev);
        } else if (ev.getTag() == CloudActionTags.VM_CREATE_ACK) {
            processVmCreateAck(ev);
        } else if (ev.getTag() == CloudActionTags.CLOUDLET_RETURN) {
            processCloudletReturn(ev);
        } else if (ev.getTag() == CloudActionTags.END_OF_SIMULATION) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Received END_OF_SIMULATION event. Broker will now proceed with final cleanup.");
            // CloudSim's core shutdown mechanism will handle calling shutdownEntity().
        } else {
            processOtherEvent(ev);
        }
    }

    @Override
    protected void processResourceCharacteristics(SimEvent ev) {
        DatacenterCharacteristics characteristics = (DatacenterCharacteristics) ev.getData();
        getDatacenterCharacteristicsList().put(characteristics.getId(), characteristics);

        if (getDatacenterCharacteristicsList().size() == getDatacenterIdsList().size()) {
            createVmsInDatacenter(getDatacenterIdsList().getFirst());
        }
    }

    @Override
    protected void processVmCreateAck(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int datacenterId = data[0];
        int vmId = data[1];
        int result = data[2];

        GuestEntity guest = VmList.getById(getGuestList(), vmId);

        if (result == CloudSimTags.TRUE) {
            getVmsToDatacentersMap().put(vmId, datacenterId);
            getGuestsCreatedList().add(guest);
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", guest.getClassName(), " #", vmId,
                    " has been created in Datacenter #", datacenterId, ", ", guest.getHost().getClassName(), " #",
                    guest.getHost().getId());
        } else {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Creation of ", guest.getClassName(), " #", vmId,
                    " failed in Datacenter #", datacenterId);
        }

        incrementVmsAcks();

        if (getVmsRequested() == getVmsAcks()) {
            if (!initialNodesSent) {
                sendNodesToControlPlane();
                initialNodesSent = true;
            }

            if (getGuestsCreatedList().size() == getGuestList().size()) {
                submitCloudletsToControlPlane();
            } else {
                boolean triedAllDatacenters = true;
                for (int nextDatacenterId : getDatacenterIdsList()) {
                    if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
                        createVmsInDatacenter(nextDatacenterId);
                        triedAllDatacenters = false;
                        break;
                    }
                }

                if (triedAllDatacenters) {
                    if (!getGuestsCreatedList().isEmpty()) {
                        submitCloudletsToControlPlane();
                    } else {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                                ": none of the required VMs/Containers could be created. CloudSim will terminate naturally when no more events remain.");
                        // No explicit terminateSimulation() here.
                    }
                }
            }
        }
    }

    private void sendNodesToControlPlane() {
        List<ObjectNode> nodeJsons = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new ObjectMapper();

        for (GuestEntity guest : getGuestsCreatedList()) {
            ObjectNode nodeJson = mapper.createObjectNode();
            nodeJson.put("id", guest.getId());
            nodeJson.put("mipsAvailable", (int) guest.getMips());
            nodeJson.put("ramAvailable", guest.getRam());

            if (guest instanceof Vm) {
                nodeJson.put("name", "vm-" + guest.getId());
            } else if (guest instanceof Container) {
                nodeJson.put("name", "container-" + guest.getId());
            } else {
                nodeJson.put("name", "guest-" + guest.getId());
            }
            nodeJsons.add(nodeJson);
        }

        String jsonPayload;
        try {
            jsonPayload = mapper.writeValueAsString(nodeJsons);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error marshalling nodes to JSON: ", e.getMessage());
            return;
        }

        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending ALL Nodes to Control Plane: ", jsonPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/nodes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": All Nodes sent successfully to Control Plane.");
            } else {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to send ALL Nodes, status: ", response.statusCode(), " Body: ", response.body());
            }
        } catch (IOException | InterruptedException e) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error sending ALL Nodes: ", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    protected void submitCloudletsToControlPlane() {
        Log.println("Submitting cloudlets to Control Plane for scheduling...");
        List<Cloudlet> successfullySubmittedToCP = new ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new ObjectMapper();

        for (Cloudlet cloudlet : getCloudletList()) {
            ObjectNode podJsonNode = mapper.createObjectNode();
            podJsonNode.put("id", cloudlet.getCloudletId());
            podJsonNode.put("name", "cloudlet-" + cloudlet.getCloudletId());
            podJsonNode.put("mipsRequested", (int) (cloudlet.getCloudletLength() / cloudlet.getNumberOfPes()));
            podJsonNode.put("ramRequested", (int) cloudlet.getCloudletFileSize());

            String podJson;
            try {
                podJson = mapper.writeValueAsString(podJsonNode);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error marshalling pod to JSON: ", e.getMessage());
                continue;
            }

            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Submitting Pod to Control Plane: ", podJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/pods"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(podJson))
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 201) {
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudlet.getCloudletId(), " submitted successfully to Control Plane.");
                    pendingCloudletsForScheduling.put(cloudlet.getCloudletId(), cloudlet);
                } else {
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to submit Pod ", cloudlet.getCloudletId(), ", status: ", response.statusCode(), " Body: ", response.body());
                }
            } catch (IOException | InterruptedException e) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error submitting Pod ", cloudlet.getCloudletId(), ": ", e.getMessage());
                Thread.currentThread().interrupt();
            }
            successfullySubmittedToCP.add(cloudlet);
        }
        getCloudletList().removeAll(successfullySubmittedToCP);

        try{
            Thread.sleep(100);
        }
        catch(Exception e){
            System.out.println(e.getMessage());
        }


        //Handle return of cloudlets

        List<Integer> cloudletIdsToProcess = new ArrayList<>(pendingCloudletsForScheduling.keySet());
        mapper = new ObjectMapper();

        for (Integer cloudletId : cloudletIdsToProcess) {
            Cloudlet cloudlet = pendingCloudletsForScheduling.get(cloudletId);
            if (cloudlet == null) continue;

            String url = CONTROL_PLANE_URL + "/pods/" + cloudletId + "/status";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    try {
                        JsonNode rootNode = mapper.readTree(responseBody);
                        String status = rootNode.get("status").asText();

                        if ("Scheduled".equals(status)) {
                            String nodeName = rootNode.has("nodeName") ? rootNode.get("nodeName").asText() : "N/A";
                            int nodeID = rootNode.has("vmId") ? rootNode.get("vmId").asInt() : -1;

                            if (nodeID != -1) {
                                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " scheduled on Node ", nodeName, " (VM ID ", nodeID, ")");
                                submitCloudletToVmInCloudSim(cloudlet, nodeID);
                                pendingCloudletsForScheduling.remove(cloudletId);
                            } else {
                                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": WARNING: Pod ", cloudletId, " scheduled, but missing or invalid VM ID in response: ", responseBody);
                                cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                                getCloudletReceivedList().add(cloudlet);
                                finishedCloudletsCount.incrementAndGet();
                                pendingCloudletsForScheduling.remove(cloudletId);
                            }
                        } else if ("Pending".equals(status)) {
                            // Still pending, needs another check in the next cycle.
                        } else if ("Unschedulable".equals(status)) {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " reported as Unschedulable by Control Plane. Marking as failed.");
                            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                            getCloudletReceivedList().add(cloudlet);
                            finishedCloudletsCount.incrementAndGet();
                            pendingCloudletsForScheduling.remove(cloudletId);
                        } else {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " has unknown status: ", status, " Response: ", responseBody);
                        }
                    } catch (Exception e) {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error parsing JSON response for Pod ", cloudletId, ": ", e.getMessage(), " Response: ", responseBody);
                    }
                } else {
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to get status for Pod ", cloudletId, ", status: ", response.statusCode(), " Body: ", response.body());
                }
            } catch (IOException | InterruptedException e) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Network error querying status for Pod ", cloudletId, ": ", e.getMessage());
                Thread.currentThread().interrupt();
            }
        }
    }

    private void submitCloudletToVmInCloudSim(Cloudlet cloudlet, int vmId) {
        GuestEntity targetVm = VmList.getById(getGuestsCreatedList(), vmId);

        if (targetVm == null) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": CRITICAL ERROR: Target VM/Container #", vmId, " not found for Cloudlet #", cloudlet.getCloudletId(), " in CloudSim's list. Marking as failed.");
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
            getCloudletReceivedList().add(cloudlet);
            finishedCloudletsCount.incrementAndGet();
            return;
        }

        if (!Log.isDisabled()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending ", cloudlet.getClass().getSimpleName(),
                    " #", cloudlet.getCloudletId(), " to " + targetVm.getClassName() + " #", targetVm.getId(), " in CloudSim.");
        }

        cloudlet.setGuestId(targetVm.getId());
        sendNow(getVmsToDatacentersMap().get(targetVm.getId()), CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
        cloudletsSubmitted++;
    }

    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        getCloudletReceivedList().add(cloudlet);
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", cloudlet.getClass().getSimpleName(), " #", cloudlet.getCloudletId(),
                " return received");
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": The number of finished Cloudlets is:", getCloudletReceivedList().size());
        cloudletsSubmitted--;
        finishedCloudletsCount.incrementAndGet();
    }

    @Override
    public void shutdownEntity() {
        super.shutdownEntity(); // Calls SimEntity.shutdownEntity() which clears incomingEvents.
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Broker_Custom entity shutting down.");
    }

    // --- Helper classes for JSON serialization (matching Go structs) ---
    private static class GoNode {
        public int id;
        public String name;
        public int mipsAvailable;
        public int ramAvailable;

        public GoNode(int id, String name, int mipsAvailable, int ramAvailable) {
            this.id = id;
            this.name = name;
            this.mipsAvailable = mipsAvailable;
            this.ramAvailable = ramAvailable;
        }
    }

    private static class GoPod {
        public int id;
        public String name;
        public int mipsRequested;
        public int ramRequested;

        public GoPod(int id, String name, int mipsRequested, int ramRequested) {
            this.id = id;
            this.name = name;
            this.mipsRequested = mipsRequested;
            this.ramRequested = ramRequested;
        }
    }
}
