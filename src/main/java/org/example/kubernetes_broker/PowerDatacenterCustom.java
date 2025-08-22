package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.core.predicates.PredicateType;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.cloudbus.cloudsim.power.PowerHost;
import org.example.metrics.TimeWeightedMetric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    Set<Integer> totalVmIdsEverAllocated;
    private final TimeWeightedMetric consolidationTW = new TimeWeightedMetric();
    boolean disableDeallocation;



    public PowerDatacenterCustom(String name, DatacenterCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
        totalVmIdsEverAllocated = new HashSet<Integer>();
        disableDeallocation = false;
    }

    public PowerDatacenterCustom(String name, DatacenterCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval, boolean disableDeallocation) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
        totalVmIdsEverAllocated = new HashSet<Integer>();
        this.disableDeallocation = disableDeallocation;
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
        int activeVMs = 0;
        int activeCloudlets = 0;
        for (HostEntity host : getVmAllocationPolicy().getHostList()) {
            for(GuestEntity vm : host.getGuestList()){
                int cloudletCount = vm.getCloudletScheduler().getCloudletExecList().size() + vm.getCloudletScheduler().getCloudletWaitingList().size();
                if(cloudletCount > 0){
                    activeVMs++;
                    activeCloudlets += cloudletCount;
                }
            }
        }

        double consolidationRatio = 0;
        if(activeVMs != 0 &&  activeCloudlets != 0) {
            consolidationRatio = (double) activeCloudlets / activeVMs;
            Log.printlnConcat(
                    CloudSim.clock() + ": We're getting a consolidationRatio of "
                            + String.format("%.2f", consolidationRatio) + "."
            );
            consolidationTW.add(CloudSim.clock(), consolidationRatio);
        }
        else{
            Log.printlnConcat(
                    CloudSim.clock() + ": No active hosts to calculate consolidation with?"
            );
        }




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

    public double getConsolidationAverage(double time){
        return consolidationTW.average(time);
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

//        /** Remove completed VMs **/
        if(!disableDeallocation){
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
                            send(this.getId(),1,CloudActionTagsEx.VM_DELAYED_DESTROY,vm);
                        }
                    }
                }
            }
        }




        Log.println();

        setLastProcessTime(currentTime);
        return minTime;
    }

    @Override
    protected void processVmCreate(SimEvent ev, boolean ack) {
        Vm vm = (Vm) ev.getData();
        totalVmIdsEverAllocated.add(vm.getId());

        // If VM specifies a preferred host, place it there
        if (vm instanceof PowerVmCustom pvm) {
            Log.println(this.getName() + ": We're trying to create a PowerVMCustom - using custom allocation logic.");
            int targetHostId = pvm.getPreferredHostId();
            HostEntity targetHost = getHostList().get(targetHostId);

            boolean result = false;

            // Allocate through the allocation policy so mapping is stored
            if (targetHost != null && getVmAllocationPolicy().getHostList().contains(targetHost)) {
                result = getVmAllocationPolicy().allocateHostForGuest(pvm, targetHost);
            } else {
                // If preferred host is invalid, fall back to normal allocation
                result = getVmAllocationPolicy().allocateHostForGuest(pvm);
            }

            if (ack) {
                int[] data = new int[]{
                        getId(),
                        pvm.getId(),
                        result ? CloudSimTags.TRUE : CloudSimTags.FALSE
                };
                send(pvm.getUserId(), CloudSim.getMinTimeBetweenEvents(),
                        CloudActionTags.VM_CREATE_ACK, data);
            }

            if (result) {
                getVmList().add(pvm);

                if (pvm.isBeingInstantiated()) {
                    pvm.setBeingInstantiated(false);
                }

                pvm.updateCloudletsProcessing(
                        CloudSim.clock(),
                        getVmAllocationPolicy().getHost(pvm)
                                .getGuestScheduler()
                                .getAllocatedMipsForGuest(pvm)
                );
            } else {
                Log.printlnConcat(CloudSim.clock(), ": Datacenter.guestAllocator: Couldn't find a host for PowerVMCustom #", pvm.getId());
            }
            return; // Skip normal allocation
        }

        // Fallback to normal allocation for all other VMs
        super.processVmCreate(ev, ack);
    }


    @Override
    public void processEvent(SimEvent ev) {
        int srcId = -1;
        CloudSimTags tag = ev.getTag();

        // Resource characteristics inquiry
        if (tag == CloudActionTagsEx.VM_DELAYED_DESTROY) {
            scheduleVMDestruction(ev);
            return;
        }
        super.processEvent(ev);
    }

    private void scheduleVMDestruction(SimEvent ev){
        Vm vm = (Vm) ev.getData();
        CloudletScheduler scheduler = vm.getCloudletScheduler();
        boolean hasActiveCloudlets =
                !scheduler.getCloudletExecList().isEmpty() ||
                        !scheduler.getCloudletWaitingList().isEmpty() ||
                        !scheduler.getCloudletFinishedList().isEmpty();

        if(!hasActiveCloudlets){
            Log.println(CloudSim.clock()  + ": VM #" + vm.getId() + " has been DEALLOCATED and DESTROYED from host");
            getVmAllocationPolicy().deallocateHostForGuest(vm);
            getVmList().remove(vm);
            int brokerId = vm.getUserId(); // This is the owning broker's ID
            sendNow(brokerId, CloudActionTags.VM_DESTROY_ACK, new int[]{
                    getId(),     // Datacenter ID
                    vm.getId(),  // VM ID
                    CloudSimTags.TRUE
            });
        }
        else {
            Log.println(CloudSim.clock()  + ": VM #" + vm.getId() + " was PREVENTED from being destroyed.");
        }

    }
}
