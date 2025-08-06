package org.example.kubernetes_broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.EX.DatacenterBrokerEX;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.lists.VmList;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Live_Kubernetes_Broker_Ex extends DatacenterBrokerEX {

    private static final String CONTROL_PLANE_URL = "http://localhost:8080";
    private final HttpClient httpClient;

    //Map of
    private final Map<Integer, Cloudlet> pendingCloudlets = new HashMap<>();
    private final Map<Integer, Cloudlet> pendingCloudletsForScheduling;

    public Live_Kubernetes_Broker_Ex(String name) throws Exception {
        super(name, -1.0F);
        this.httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        this.pendingCloudletsForScheduling = new HashMap<>();
    }

    public Live_Kubernetes_Broker_Ex(String name, double lifeLength) throws Exception {
        super(name,lifeLength);
        this.httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        this.pendingCloudletsForScheduling = new HashMap<>();
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
            sendAllActiveNodesToControlPlane();
            if (getGuestsCreatedList().size() == getGuestList().size()) {
                submitCloudlets();
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
                        submitCloudlets();
                    } else {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                                ": none of the required VMs/Containers could be created. CloudSim will terminate naturally when no more events remain.");
                        // No explicit terminateSimulation() here.
                    }
                }
            }
        }
    }

    private void sendAllActiveNodesToControlPlane() {
        List<ObjectNode> nodeJsons = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        for (GuestEntity guest : getGuestsCreatedList()) {
            ObjectNode nodeJson = mapper.createObjectNode();
            nodeJson.put("id", guest.getId());
            nodeJson.put("mipsAvailable", (int) guest.getMips());
            nodeJson.put("ramAvailable", guest.getRam());
            nodeJson.put("pes", guest.getNumberOfPes());
            nodeJson.put("bw", guest.getBw());
            nodeJson.put("size", guest.getSize());
            nodeJson.put("type", guest instanceof Vm ? "vm" : "container");

            String name = guest instanceof Vm ? "vm-" + guest.getId()
                    : guest instanceof Container ? "container-" + guest.getId()
                    : "guest-" + guest.getId();
            nodeJson.put("name", name);

            nodeJsons.add(nodeJson);
        }

        if (nodeJsons.isEmpty()) return;

        try {
            String payload = mapper.writeValueAsString(nodeJsons);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/nodes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println(CloudSim.clock() + ": Synced active nodes: " + payload);
            } else {
                Log.println(CloudSim.clock() + ": Failed to sync nodes: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void submitCloudlets() {
        Log.println("Submitting all cloudlets to Control Plane in a single batch...");
        Log.println("Syncing all nodes again first to make sure...");
        sendAllActiveNodesToControlPlane();
        Log.printlnConcat("Done syncing nodes. Continuing to the cloudlets batch...");

        // 1. Prepare payload
        String requestBody = serializeCloudletsForSubmission(getCloudletList());
        if (requestBody == null){
            Log.printlnConcat(CloudSim.clock(),": Request body is null?");
            return;
        };

        // 2. Submit to control plane
        ArrayNode scheduledPods = submitCloudletBatchToMiddleware(requestBody);
        if (scheduledPods == null){
            Log.printlnConcat(CloudSim.clock(),": No pods to schedule. Skipping pod response process");
            return;
        }

        // 3. Process scheduling result
        processScheduledPodsResponse(scheduledPods);
    }

    private String serializeCloudletsForSubmission(List<Cloudlet> cloudletList) {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> podJsonList = new ArrayList<>();

        for (Cloudlet cloudlet : cloudletList) {
            ObjectNode podJson = mapper.createObjectNode();
            podJson.put("id", cloudlet.getCloudletId());
            podJson.put("name", "cloudlet-" + cloudlet.getCloudletId());
            podJson.put("length", cloudlet.getCloudletLength());
            podJson.put("pes", cloudlet.getNumberOfPes());
            podJson.put("fileSize", cloudlet.getCloudletFileSize());
            podJson.put("outputSize", cloudlet.getCloudletOutputSize());
            podJson.put("utilizationCpu", cloudlet.getUtilizationModelCpu().getUtilization(0));
            podJson.put("utilizationRam", cloudlet.getUtilizationModelRam().getUtilization(0));
            podJson.put("utilizationBw", cloudlet.getUtilizationModelBw().getUtilization(0));
            podJsonList.add(podJson);
            pendingCloudletsForScheduling.put(cloudlet.getCloudletId(), cloudlet);
        }

        try {
            return mapper.writeValueAsString(podJsonList);
        } catch (Exception e) {
            Log.printlnConcat(getName(), ": Error serializing cloudlets: ", e.getMessage());
            return null;
        }
    }

    private String serializeSingleCloudletForSubmission(Cloudlet cloudlet) {
        return serializeCloudletsForSubmission(List.of(cloudlet));
    }

    private ArrayNode submitCloudletBatchToMiddleware(String requestBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/schedule-pods"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Requesting: " + CONTROL_PLANE_URL + "/schedule-pods");

            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                return (ArrayNode) mapper.readTree(response.body());
            } else {
                Log.printlnConcat(getName(), ": Failed to batch schedule cloudlets. HTTP ", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            Log.printlnConcat(getName(), ": Error submitting cloudlets batch: ", e.getMessage());
            return null;
        }
    }

    private void processScheduledPodsResponse(ArrayNode scheduledPods) {
        Log.printlnConcat(getName(), ": Processing pods response");
        Log.printlnConcat(getName(), ": the array looks like so: ", scheduledPods);
        for (JsonNode podNode : scheduledPods) {
            Log.printlnConcat(getName(), ": Entering scheduledPods");
            int cloudletId = podNode.get("id").asInt();
            String status = podNode.get("status").asText();
            int nodeID = podNode.has("vmId") ? podNode.get("vmId").asInt() : -1;


            Cloudlet cloudlet = pendingCloudletsForScheduling.get(cloudletId);
            if (cloudlet == null){
                Log.printlnConcat(getName(), ": Pod ",cloudletId, " not found in Pending Cloudlets for scheduling. It was supposed to be in Pod/VM ",nodeID);
                continue;
            };

            Log.printlnConcat(CloudSim.clock() + ": For Cloudlet #" + cloudletId + " the status is " + status);
            switch (status) {
                case "Scheduled" -> {
                    Log.printlnConcat(CloudSim.clock() + ": For Cloudlet #" + cloudletId + " scheduled at node " + nodeID);
                    String nodeName = podNode.has("nodeName") ? podNode.get("nodeName").asText() : "N/A";
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId,
                            " scheduled on Node ", nodeName, " (VM ID ", nodeID, ")");
                    if (nodeID != -1) {
                        submitCloudletToVmInCloudSim(cloudlet, nodeID);
                    } else {
                        cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                        getCloudletReceivedList().add(cloudlet);
                    }
                }
                case "Unschedulable", "Unknown" -> {
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " is unschedulable or unknown.");
                    getCloudletList().remove(cloudlet);
                    pendingCloudlets.put(cloudletId, cloudlet);
                    //cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
                    //getCloudletReceivedList().add(cloudlet);
                }
            }
            Log.printlnConcat(getName(), ": Done processing pod response");
            pendingCloudletsForScheduling.remove(cloudletId);
        }

        Log.println("Finished scheduling batch. Submitting to CloudSim.");
        super.submitCloudlets();  // If still needed
        Log.println("CloudSim finished scheduling batch.");
    }

    @Override
    protected void processCloudletReturn(SimEvent ev) {
//        sendAllActiveNodesToControlPlane();
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", cloudlet.getClass().getSimpleName()," #", cloudlet.getCloudletId(), " return received");
        updateMiddleware(cloudlet);

        if (getLifeLength() <= 0) {
            // Will kill the broker if there are no more cloudlets.
            super.processCloudletReturn(ev);
        } else {
            getCloudletReceivedList().add(cloudlet);
            cloudletsSubmitted--;
        }
    }

    private void updateMiddleware(Cloudlet cloudlet) {
        Log.printlnConcat("Deleting cloudlet ", cloudlet.getCloudletId(), " from the control panel.");
        String jsonPayload = serializeSingleCloudletForSubmission(cloudlet);
        ArrayNode newCloudlets = deleteCloudletAndWait(jsonPayload);
        if (newCloudlets == null || newCloudlets.isEmpty()) {
            Log.println("No new cloudlets to submit.");
        }
        else {
            Cloudlet newcloudlet = pendingCloudlets.get(newCloudlets.get(0).get("id").asInt());
            getCloudletList().add(newcloudlet);
            Log.printlnConcat("New cloudlet to submit: ", newcloudlet.getCloudletId(), ". Proceeding...");
            processScheduledPodsResponse(newCloudlets);
        }

    }
    public ArrayNode deleteCloudletAndWait(String jsonPayload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/pods/update-state"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Log.println("Cloudlet deletion and wait successful. Response: " + response.body());

                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.body());

                if (rootNode.isArray()) {
                    return (ArrayNode) rootNode;
                } else {
                    Log.println("Expected an array in response, got: " + rootNode);
                    return null;
                }
            } else {
                Log.println("Failed to delete cloudlet. Status: " + response.statusCode() + " Body: " + response.body());
                return null;
            }
        } catch (IOException | InterruptedException e) {
            Log.println("Error during cloudlet deletion request: " + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }




    private void submitCloudletToVmInCloudSim(Cloudlet cloudlet, int vmId) {
        GuestEntity targetVm = VmList.getById(getGuestsCreatedList(), vmId);

        if (targetVm == null) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": CRITICAL ERROR: Target VM/Container #", vmId, " not found for Cloudlet #", cloudlet.getCloudletId(), " in CloudSim's list. Marking as failed.");
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
            getCloudletReceivedList().add(cloudlet);
            return;
        }

        cloudlet.setGuestId(targetVm.getId());
    }


    public void sendResetRequestToControlPlane() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/reset"))
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Log.println("Sent reset request to Control Plane.");
            } else {
                Log.println("Failed to reset Control Plane. Status: " + response.statusCode()
                        + ", Body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Log.println("Error sending reset request to Control Plane: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
