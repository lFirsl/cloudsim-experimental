package org.example;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Log;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Broker_Custom extends DatacenterBroker {

    // Base URL for the Go Kubernetes Sim Control Plane
    private static final String CONTROL_PLANE_URL = "http://localhost:8080";
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;

    // Map to keep track of pods that have been submitted to the control plane
    // and are awaiting scheduling decisions.
    private final Map<Integer, Cloudlet> pendingCloudletsForScheduling;

    public Broker_Custom(String name) throws Exception {
        super(name);
        this.httpClient = HttpClient.newHttpClient();
        // Scheduler for periodic tasks like polling pod status
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.pendingCloudletsForScheduling = new HashMap<>();
    }

    @Override
    public void startEntity() {
        super.startEntity();
        // Initial request for datacenter characteristics, which will eventually lead to VM creation
        schedule(getId(), 0, CloudActionTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    @Override
    protected void processResourceCharacteristics(SimEvent ev) {
        DatacenterCharacteristics characteristics = (DatacenterCharacteristics) ev.getData();
        getDatacenterCharacteristicsList().put(characteristics.getId(), characteristics);

        if (getDatacenterCharacteristicsList().size() == getDatacenterIdsList().size()) {
            // All datacenter characteristics received.
            // Now, create VMs (Nodes) and send their info to the control plane.
            // We'll use the first datacenter for VM creation for simplicity in this POC.
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

            // After a VM (Node) is created in CloudSim, send its details to the Go control plane.
            sendNodesToControlPlane();

        } else {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Creation of ", guest.getClassName(), " #", vmId,
                    " failed in Datacenter #", datacenterId);
        }

        incrementVmsAcks();

        // All the requested VMs have been created or attempted.
        // If all VMs are created, we can now submit cloudlets (pods) to the control plane.
        if (getGuestsCreatedList().size() == getGuestList().size() - getVmsDestroyed()) {
            // Instead of directly submitting cloudlets to VMs, we send them to the control plane.
            submitCloudletsToControlPlane();
            // Start polling for pod status after submitting all pods
            startPodStatusPolling();
        } else {
            // If some VMs were not created, try other datacenters or abort if no VMs can be created.
            if (getVmsRequested() == getVmsAcks()) {
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
                        startPodStatusPolling();
                    } else {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(),
                                ": none of the required VMs could be created. Aborting");
                        finishExecution();
                    }
                }
            }
        }
    }

    /**
     * Sends the current list of created VMs (simulated Nodes) to the Go control plane.
     */
    private void sendNodesToControlPlane() {
        List<String> nodeJsons = new ArrayList<>();
        for (GuestEntity vm : getGuestsCreatedList()) {
            nodeJsons.add(convertVmToNodeJson(vm));
        }

        String jsonPayload = "[" + String.join(",", nodeJsons) + "]";
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending Nodes to Control Plane: ", jsonPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/nodes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        // Execute the request asynchronously to avoid blocking CloudSim simulation
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::statusCode)
                .thenAccept(statusCode -> {
                    if (statusCode == 200) {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Nodes sent successfully to Control Plane.");
                    } else {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to send Nodes, status: ", statusCode);
                    }
                })
                .exceptionally(e -> {
                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error sending Nodes: ", e.getMessage());
                    return null;
                });
    }

    /**
     * Submits all pending Cloudlets (simulated Pods) to the Go control plane for scheduling.
     */
    protected void submitCloudletsToControlPlane() {
        Log.println("Submitting cloudlets to Control Plane for scheduling...");
        List<Cloudlet> successfullySubmittedToCP = new ArrayList<>();

        for (Cloudlet cloudlet : getCloudletList()) {
            String podJson = convertCloudletToPodJson(cloudlet);
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Submitting Pod to Control Plane: ", podJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONTROL_PLANE_URL + "/pods"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(podJson))
                    .build();

            // Execute asynchronously
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::statusCode)
                    .thenAccept(statusCode -> {
                        if (statusCode == 201) { // 201 Created is expected for new resources
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudlet.getCloudletId(), " submitted successfully to Control Plane.");
                            // Mark as pending for scheduling decision
                            pendingCloudletsForScheduling.put(cloudlet.getCloudletId(), cloudlet);
                        } else {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to submit Pod ", cloudlet.getCloudletId(), ", status: ", statusCode);
                        }
                    })
                    .exceptionally(e -> {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error submitting Pod ", cloudlet.getCloudletId(), ": ", e.getMessage());
                        return null;
                    });
            successfullySubmittedToCP.add(cloudlet);
        }
        // Clear the list of cloudlets that are now being managed by the control plane
        getCloudletList().removeAll(successfullySubmittedToCP);
    }

    /**
     * Starts a scheduled task to periodically poll the status of pending pods from the control plane.
     */
    private void startPodStatusPolling() {
        // Poll every 1 second
        scheduler.scheduleAtFixedRate(this::queryPodStatusAndSchedule, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Queries the control plane for the status of pending pods and schedules them in CloudSim
     * if they are reported as "Scheduled".
     */
    private void queryPodStatusAndSchedule() {
        // Create a copy to avoid ConcurrentModificationException if we remove elements
        List<Integer> cloudletIdsToProcess = new ArrayList<>(pendingCloudletsForScheduling.keySet());

        if (cloudletIdsToProcess.isEmpty()) {
            // If no more pending cloudlets, stop polling
            if (!getCloudletList().isEmpty() || cloudletsSubmitted > 0) {
                // If there are still cloudlets in the original list (e.g., failed to submit)
                // or if some are still running, keep polling.
                // Otherwise, if all are processed and no new ones, we can stop.
            } else {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": No more pending cloudlets for scheduling. Stopping polling.");
                scheduler.shutdown();
                return;
            }
        }

        for (Integer cloudletId : cloudletIdsToProcess) {
            Cloudlet cloudlet = pendingCloudletsForScheduling.get(cloudletId);
            if (cloudlet == null) continue; // Should not happen

            String url = CONTROL_PLANE_URL + "/pods/" + cloudletId + "/status";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            String responseBody = response.body();
                            // Parse the JSON response to check the pod's status and assigned node
                            // For simplicity, we'll do a basic string check.
                            // In a real app, use a JSON parsing library (e.g., Jackson, Gson).
                            if (responseBody.contains("\"status\":\"Scheduled\"")) {
                                try {
                                    // Extract nodeName and nodeID from the JSON
                                    // This is a very fragile way to parse JSON. Use a library!
                                    int nodeNameIndex = responseBody.indexOf("\"nodeName\":\"");
                                    int nodeNameEndIndex = responseBody.indexOf("\"", nodeNameIndex + 12);
                                    String nodeName = responseBody.substring(nodeNameIndex + 12, nodeNameEndIndex);

                                    int nodeIdIndex = responseBody.indexOf("\"vmId\":");
                                    int nodeIdEndIndex = responseBody.indexOf(",", nodeIdIndex + 7);
                                    if (nodeIdEndIndex == -1) nodeIdEndIndex = responseBody.indexOf("}", nodeIdIndex + 7); // Handle last field
                                    int nodeID = Integer.parseInt(responseBody.substring(nodeIdIndex + 7, nodeIdEndIndex));

                                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " scheduled on Node ", nodeName, " (VM ID ", nodeID, ")");

                                    // Now, actually submit the cloudlet to the VM in CloudSim
                                    submitCloudletToVmInCloudSim(cloudlet, nodeID);

                                    // Remove from pending list as it's now scheduled in CloudSim
                                    pendingCloudletsForScheduling.remove(cloudletId);

                                } catch (Exception e) {
                                    Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error parsing pod status for ", cloudletId, ": ", e.getMessage());
                                }
                            } else if (responseBody.contains("\"status\":\"Unschedulable\"")) {
                                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " reported as Unschedulable by Control Plane.");
                                // Handle unschedulable pod (e.g., mark as failed, retry, etc.)
                                pendingCloudletsForScheduling.remove(cloudletId); // Remove from pending
                            }
                        } else if (response.statusCode() == 404) {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Pod ", cloudletId, " not found on Control Plane (might be processed).");
                            // Could mean it was already processed and removed, or an error.
                            // For now, keep it in pending, or implement a retry mechanism.
                        } else {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Failed to get status for Pod ", cloudletId, ", status: ", response.statusCode());
                        }
                    })
                    .exceptionally(e -> {
                        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error querying status for Pod ", cloudletId, ": ", e.getMessage());
                        return null;
                    });
        }
    }

    /**
     * Submits a Cloudlet to a specific VM in the CloudSim environment.
     * This method is called after the control plane has made a scheduling decision.
     *
     * @param cloudlet The Cloudlet to be submitted.
     * @param vmId     The ID of the VM (Node) it should be submitted to.
     */
    private void submitCloudletToVmInCloudSim(Cloudlet cloudlet, int vmId) {
        GuestEntity vm = VmList.getById(getGuestsCreatedList(), vmId);

        if (vm == null) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Error: VM #", vmId, " not found for Cloudlet #", cloudlet.getCloudletId(), ". Cannot submit.");
            return;
        }

        if (!Log.isDisabled()) {
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending ", cloudlet.getClass().getSimpleName(),
                    " #", cloudlet.getCloudletId(), " to " + vm.getClassName() + " #", vm.getId(), " in CloudSim.");
        }

        cloudlet.setGuestId(vm.getId());
        sendNow(getVmsToDatacentersMap().get(vm.getId()), CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
        cloudletsSubmitted++;
        getCloudletSubmittedList().add(cloudlet);
    }


    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        getCloudletReceivedList().add(cloudlet);
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": ", cloudlet.getClass().getSimpleName(), " #", cloudlet.getCloudletId(),
                " return received");
        Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": The number of finished Cloudlets is:", getCloudletReceivedList().size());
        cloudletsSubmitted--; // Decrement count of cloudlets actively running in CloudSim

        if (getCloudletList().isEmpty() && pendingCloudletsForScheduling.isEmpty() && cloudletsSubmitted == 0) {
            // All initial cloudlets processed, no pending ones for external scheduling, and no active ones.
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": All Cloudlets executed. Finishing...");
            clearDatacenters();
            finishExecution();
            scheduler.shutdownNow(); // Ensure the scheduler is stopped
        }
    }

    /**
     * Helper method to convert CloudSim Cloudlet data to a simplified Kubernetes Pod JSON.
     * For POC: minimal fields (e.g., ID, required MIPS/RAM).
     */
    private String convertCloudletToPodJson(Cloudlet cloudlet) {
        // Using String.format for basic JSON. For production, use a proper JSON library (Jackson, Gson).
        return String.format(
                "{\"id\": %d, \"name\": \"cloudlet-%d\", \"mipsRequested\": %d, \"ramRequested\": %d}",
                cloudlet.getCloudletId(), cloudlet.getCloudletId(),
                (int) cloudlet.getCloudletLength() / cloudlet.getNumberOfPes(), // Rough MIPS per PE
                (int) cloudlet.getCloudletFileSize() // Just an example, map to RAM as needed
        );
    }

    /**
     * Helper method to convert CloudSim VM/Host data to simplified Kubernetes Node JSON.
     * For POC: ID, name, available MIPS/RAM.
     */
    private String convertVmToNodeJson(GuestEntity vm) {
        // Using String.format for basic JSON. For production, use a proper JSON library (Jackson, Gson).
        return String.format(
                "{\"id\": %d, \"name\": \"vm-%d\", \"mipsAvailable\": %d, \"ramAvailable\": %d}",
                vm.getId(), vm.getId(),
                (int) vm.getMips(), // Example: VM's total MIPS
                vm.getRam() // Example: VM's total RAM
        );
    }

    @Override
    public void shutdownEntity() {
        super.shutdownEntity();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Scheduler shut down.");
        }
    }
}
