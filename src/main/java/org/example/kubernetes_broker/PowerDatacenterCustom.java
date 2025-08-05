package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.container.core.PowerContainer;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.core.predicates.PredicateType;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.cloudbus.cloudsim.power.PowerHost;

import java.util.ArrayList;
import java.util.List;

public class PowerDatacenterCustom extends PowerDatacenter {
    /**
     * Instantiates a new PowerDatacenter.
     *
     * @param name               the datacenter name
     * @param characteristics    the datacenter characteristics
     * @param vmAllocationPolicy the vm provisioner
     * @param storageList        the storage list
     * @param schedulingInterval the scheduling interval
     * @throws Exception the exception
     */

    double totalUsedMips = 0;
    double totalCapacity = 0;


    public PowerDatacenterCustom(String name, DatacenterCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
    }

    @Override
    protected void updateCloudletProcessing() {
        if (getCloudletSubmitted() == -1 || getCloudletSubmitted() == CloudSim.clock()) {
            CloudSim.cancelAll(getId(), new PredicateType(CloudActionTags.VM_DATACENTER_EVENT));
            schedule(getId(), getSchedulingInterval(), CloudActionTags.VM_DATACENTER_EVENT);
            return;
        }
        double currentTime = CloudSim.clock();

        //Bin efficiency prototype. Only addition to updateCloudletProcessing thus far!
        //...could probably make this into it's own function and then just call "super.updateCloudletProcessing".
        for (HostEntity host : getVmAllocationPolicy().getHostList()) {
            if(host instanceof PowerHost hostPower) {
                if (hostPower.getUtilizationOfCpu() <= 0.0) {
                    Log.printlnConcat("Skipping over " + hostPower.getId() + " due to no CPU utilization");
                    continue;
                }

                double hostUtil = hostPower.getUtilizationOfCpu(); // value between 0 and 1
                totalUsedMips += hostPower.getUtilizationMips();
                totalCapacity += hostPower.getTotalMips();
            }
        }
        double packingEfficiency = totalCapacity > 0
                ? (totalUsedMips / totalCapacity) * 100
                : 0;

        Log.printlnConcat(
                CloudSim.clock() + ": We're getting a bin-packing efficiency of "
                        + String.format("%.2f", packingEfficiency) + "%"
        );

        // if some time passed since last processing
        if (currentTime > getLastProcessTime()) {
            Log.print(currentTime + " ");

            double minTime = updateCloudetProcessingWithoutSchedulingFutureEventsForce();

            if (!isDisableMigrations()) {
                List<VmAllocationPolicy.GuestMapping> migrationMap = getVmAllocationPolicy().optimizeAllocation(
                        getVmList());

                if (migrationMap != null) {
                    for (VmAllocationPolicy.GuestMapping migrate : migrationMap) {
                        Vm vm = (Vm) migrate.vm();
                        PowerHost targetHost = (PowerHost) migrate.host();
                        PowerHost oldHost = (PowerHost) vm.getHost();

                        if (oldHost == null) {
                            Log.formatLine(
                                    "%.2f: Migration of VM #%d to Host #%d is started",
                                    currentTime,
                                    vm.getId(),
                                    targetHost.getId());
                        } else {
                            Log.formatLine(
                                    "%.2f: Migration of VM #%d from Host #%d to Host #%d is started",
                                    currentTime,
                                    vm.getId(),
                                    oldHost.getId(),
                                    targetHost.getId());
                        }

                        targetHost.addMigratingInGuest(vm);
                        incrementMigrationCount();

                        /** VM migration delay = RAM / bandwidth **/
                        // we use BW / 2 to model BW available for migration purposes, the other
                        // half of BW is for VM communication
                        // around 16 seconds for 1024 MB using 1 Gbit/s network
                        send(
                                getId(),
                                vm.getRam() / ((double) targetHost.getBw() / (2 * 8000)),
                                CloudActionTags.VM_MIGRATE,
                                migrate);
                    }
                }
            }

            // schedules an event to the next time
            if (minTime != Double.MAX_VALUE) {
                CloudSim.cancelAll(getId(), new PredicateType(CloudActionTags.VM_DATACENTER_EVENT));
                send(getId(), getSchedulingInterval(), CloudActionTags.VM_DATACENTER_EVENT);
            }

            setLastProcessTime(currentTime);
        }
    }

    @Override
    protected double updateCloudetProcessingWithoutSchedulingFutureEventsForce() {
        double currentTime = CloudSim.clock();
        double minTime = Double.MAX_VALUE;
        double timeDiff = currentTime - getLastProcessTime();
        double timeFrameDatacenterEnergy = 0.0;

        Log.println("\n\n--------------------------------------------------------------\n\n");
        Log.formatLine("New resource usage for the time frame starting at %.2f:", currentTime);

        for (PowerHost host : this.<PowerHost> getHostList()) {
            Log.println();

            double time = host.updateCloudletsProcessing(currentTime); // inform VMs to update processing
            if (time < minTime) {
                minTime = time;
            }

            Log.formatLine(
                    "%.2f: [Host #%d] utilization is %.2f%%",
                    currentTime,
                    host.getId(),
                    host.getUtilizationOfCpu() * 100);
        }

        if (timeDiff > 0) {
            Log.formatLine(
                    "\nEnergy consumption for the last time frame from %.2f to %.2f:",
                    getLastProcessTime(),
                    currentTime);

            for (PowerHost host : this.<PowerHost> getHostList()) {
                double previousUtilizationOfCpu = host.getPreviousUtilizationOfCpu();
                double utilizationOfCpu = host.getUtilizationOfCpu();
                double timeFrameHostEnergy = host.getEnergyLinearInterpolation(
                        previousUtilizationOfCpu,
                        utilizationOfCpu,
                        timeDiff);
                timeFrameDatacenterEnergy += timeFrameHostEnergy;

                Log.println();
                Log.formatLine(
                        "%.2f: [Host #%d] utilization at %.2f was %.2f%%, now is %.2f%%",
                        currentTime,
                        host.getId(),
                        getLastProcessTime(),
                        previousUtilizationOfCpu * 100,
                        utilizationOfCpu * 100);
                Log.formatLine(
                        "%.2f: [Host #%d] energy is %.2f W*sec",
                        currentTime,
                        host.getId(),
                        timeFrameHostEnergy);
            }

            Log.formatLine(
                    "\n%.2f: Data center's energy is %.2f W*sec\n",
                    currentTime,
                    timeFrameDatacenterEnergy);
        }

        setPower(getPower() + timeFrameDatacenterEnergy);

        /** Remove completed VMs **/
//         NOPE - This custom PowerDatacentre removes the deallocation functionality - for now.
        for (PowerHost host : this.<PowerHost>getHostList()) {
            for (GuestEntity guest : new ArrayList<GuestEntity>(host.getGuestList())) {
                if (guest.isInMigration()) continue;

                if (guest instanceof Vm vm) {
                    CloudletScheduler scheduler = vm.getCloudletScheduler();
                    boolean hasActiveCloudlets =
                            !scheduler.getCloudletExecList().isEmpty() ||
                                    !scheduler.getCloudletWaitingList().isEmpty() ||
                                    !scheduler.getCloudletFinishedList().isEmpty();

                    if (!hasActiveCloudlets) {
                        Log.println(CloudSim.clock()  + ": VM #" + vm.getId() + " has been DEALLOCATED and DESTROYED from host #" + host.getId());
                        getVmAllocationPolicy().deallocateHostForGuest(vm);
                        getVmList().remove(vm);
                        int brokerId = vm.getUserId(); // This is the owning broker's ID
                        sendNow(brokerId, CloudActionTags.VM_DESTROY_ACK, new int[]{
                                getId(),     // Datacenter ID
                                vm.getId(),  // VM ID
                                CloudSimTags.TRUE
                        });
                    }
                }
            }
        }

        Log.println();

        setLastProcessTime(currentTime);
        return minTime;
    }
}
