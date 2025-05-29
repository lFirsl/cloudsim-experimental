package org.example;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudActionTags;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.lists.VmList;

import java.util.ArrayList;
import java.util.List;

public class Broker_Custom extends DatacenterBroker {

    /** Next guest to which send the cloudlet */
    private int guestIndex = 0;

    public Broker_Custom(String name) throws Exception {
        super(name);
    }

    protected void submitCloudlets() {
        System.out.println("Submitting cloudlets...from custom broker!");
        List<Cloudlet> successfullySubmitted = new ArrayList<>();
        for (Cloudlet cloudlet : getCloudletList()) {
            GuestEntity vm;
            // if user didn't bind this cloudlet and it has not been executed yet
            if (cloudlet.getGuestId() == -1) {
                vm = getGuestsCreatedList().get(guestIndex);
            } else { // submit to the specific vm, default
                vm = VmList.getById(getGuestsCreatedList(), cloudlet.getGuestId());
                if (vm == null) { // vm was not created
                    vm = VmList.getById(getGuestList(), cloudlet.getGuestId()); // check if exists in the submitted list

                    if(!Log.isDisabled()) {
                        if (vm != null) {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Postponing execution of cloudlet ",
                                    cloudlet.getCloudletId(), ": bount ", vm.getClassName(), " #", vm.getId(), " not available");
                        } else {
                            Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Postponing execution of cloudlet ",
                                    cloudlet.getCloudletId(), ": bount guest entity of id ", cloudlet.getGuestId(), " doesn't exist");
                        }
                    }
                    continue;
                }
            }

            if (!Log.isDisabled()) {
                Log.printlnConcat(CloudSim.clock(), ": ", getName(), ": Sending ", cloudlet.getClass().getSimpleName(),
                        " #", cloudlet.getCloudletId(), " to " + vm.getClassName() + " #", vm.getId());
            }

            cloudlet.setGuestId(vm.getId());
            sendNow(getVmsToDatacentersMap().get(vm.getId()), CloudActionTags.CLOUDLET_SUBMIT, cloudlet);
            cloudletsSubmitted++;
            guestIndex = (guestIndex + 1) % getGuestsCreatedList().size();
            getCloudletSubmittedList().add(cloudlet);
            successfullySubmitted.add(cloudlet);
        }

        // remove submitted cloudlets from waiting list
        getCloudletList().removeAll(successfullySubmitted);
    }

    /**
     * Helper method to convert CloudSim Cloudlet data to a simplified Kubernetes Pod JSON.
     * For POC: minimal fields (e.g., ID, required MIPS/RAM).
     */
    private String convertCloudletToPodJson(Cloudlet cloudlet) {
        // This is a simplified example. In a real scenario, use a JSON library.
        // You'd map Cloudlet's PE, MIPS, RAM requirements to Pod's resource requests.
        // For POC, just send essential info.
        return String.format(
                "{ \"id\": %d, \"name\": \"cloudlet-%d\", \"mipsRequested\": %d, \"ramRequested\": %d }",
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
        // In CloudSim 7G, VMs are GuestEntities that run on Hosts.
        // Your "Node" in K8s terms would likely map to a CloudSim Host.
        // If you are simulating K8s placing pods onto VMs, then the VM itself is the node.
        // For this POC, let's assume VMs are "nodes" for the K8s scheduler.
        // In a more complex scenario, you'd map Host properties.
        return String.format(
                "{ \"id\": %d, \"name\": \"vm-%d\", \"mipsAvailable\": %d, \"ramAvailable\": %d }",
                vm.getId(), vm.getId(),
                (int) vm.getMips(), // Example: VM's total MIPS
                vm.getRam() // Example: VM's total RAM
        );
    }
}
