package org.example.examples;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.core.VirtualEntity;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared; // Using TimeShared for VMs
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared; // Using TimeShared for Cloudlets
import org.example.kubernetes_broker.Live_Kubernetes_Broker;

import java.text.DecimalFormat;
import java.util.*;

public class Custom_Broker_Example {

    /** The host list. */
    private static List<HostEntity> hostList; // Initialize in main
    /** The cloudlet list. */
    private static List<Cloudlet> cloudletList;
    /** The vmlist. */
    private static List<VirtualEntity> vmlist;
    /** the containerlist */
    private static List<GuestEntity> containerlist;

    // HOST Specifications
    private static int HOST_MIPS = 10000; // Increased MIPS for hosts
    private static int HOST_RAM = 16384; // 16GB RAM
    private static long HOST_STORAGE = 1000000; // 1TB storage
    private static int HOST_BW = 10000;
    private static int HOST_CORES = 8; // More cores for hosts

    // TEST settings
    private static int HOST_NUM = 3;
    private static int VMS_PER_HOST = 2; // Number of VMs to create per physical host
    private static int CONTAINERS_PER_VM = 4;
    private static int CLOUDLETS_PER_CONTAINER = 10; // Reduced for easier management

    public static void main(String[] args) {
        try{
            //Initiate CloudSim
            CloudSim.init(1,Calendar.getInstance(),false);

            //Get a broker
            Live_Kubernetes_Broker broker = createBroker();

            hostList = new ArrayList<>(); // Initialize hostList here
            int hostID = 0;
            for(int i = 0; i < HOST_NUM; i++){
                List<Pe> peList = new ArrayList<>();
                for(int j = 0; j < HOST_CORES; j++){
                    peList.add(new Pe(j, new PeProvisionerSimple(HOST_MIPS / HOST_CORES))); // Distribute MIPS among PEs
                }

                hostList.add(new Host(
                        hostID++,
                        new RamProvisionerSimple(HOST_RAM),
                        new BwProvisionerSimple(HOST_BW),
                        HOST_STORAGE,
                        peList,
                        new VmSchedulerTimeShared(peList) // Using TimeShared for Host VM scheduling
                ));
            }

            Log.printLine("Created " + hostList.size() + " hosts.");

            vmlist = new ArrayList<>();
            int vmID = 0;
            // Define VMs with reasonable resources that can fit on hosts
            int VM_MIPS_PER_PE = 500; // MIPS per VM PE
            int VM_CORES = 1; // Each VM has 1 core for simplicity
            int VM_RAM = 1024; // 1GB RAM per VM
            int VM_BW = 1000;
            long VM_STORAGE = 10000; // 10GB storage

            for (int i = 0; i < HOST_NUM * VMS_PER_HOST; i++) { // Create VMS_PER_HOST VMs for each physical host
                List<Pe> vmPeList = new ArrayList<>();
                for (int j = 0; j < VM_CORES; j++) {
                    vmPeList.add(new Pe(j, new PeProvisionerSimple(VM_MIPS_PER_PE)));
                }

                VirtualEntity vm = new Vm(
                        vmID++, broker.getId(),
                        VM_MIPS_PER_PE * VM_CORES, // Total VM MIPS
                        VM_CORES,
                        VM_RAM,
                        VM_BW,
                        VM_STORAGE, // VM image size
                        "Xen",
                        new CloudletSchedulerTimeShared(),
                        new VmSchedulerTimeShared(vmPeList), // VM's internal scheduler
                        new RamProvisionerSimple(VM_RAM),
                        new BwProvisionerSimple(VM_BW),
                        vmPeList
                );
                vmlist.add(vm);
            }
            Log.printLine("Created " + vmlist.size() + " VMs.");


            // Create Datacenters
            // Pass the hostList to the Datacenter constructor
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // submit vm list to the broker
            broker.submitGuestList(vmlist); // These are VMs that will be created on the hosts

            // Derive container specifications as to avoid a VM using more than 80%
            // Ensure container resources are less than VM resources
            int CONTAINER_MIPS = 200; // MIPS per container
            int CONTAINER_RAM = 256;   // RAM per container
            int CONTAINER_BW = 200;
            long CONTAINER_STORAGE = 500;
            int CONTAINER_CORES = 1;

            // Create containers
            containerlist = new ArrayList<>();
            int containerID = 0;
            for(int i = 0; i < vmlist.size() * CONTAINERS_PER_VM; i++) { // Create containers for all VMs
                GuestEntity container = new Container(containerID++, broker.getId(),
                        CONTAINER_MIPS, CONTAINER_CORES,
                        CONTAINER_RAM, CONTAINER_BW,
                        CONTAINER_STORAGE, "Docker",
                        new CloudletSchedulerTimeShared()
                );
                // Containers are not directly assigned to VMs here.
                // Their scheduling will be handled by the external control plane.
                // The `setHost` call was relevant if CloudSim was doing the initial container placement.
                // For now, we just create them and let the external scheduler decide.
                containerlist.add(container);
            }
            Log.printLine("Created " + containerlist.size() + " Containers.");

            // submit container list to the broker
            // These are the "pods" that will be sent to the control plane
            broker.submitGuestList(containerlist);


            // Create Cloudlets
            cloudletList = new ArrayList<>();
            // Adjust Cloudlet length to be reasonable for a single PE of a container
            long cloudletLength = 10000; // Much smaller, more realistic for a single task
            long fileSize = 300;
            long outputSize = 300;
            UtilizationModel utilizationModel = new UtilizationModelFull();

            int cloudletID = 0;
            for (int i = 0; i < containerlist.size() * CLOUDLETS_PER_CONTAINER; i++) {
                Cloudlet cloudlet = new Cloudlet(
                        cloudletID++, cloudletLength, 1, // 1 PE for simplicity
                        fileSize, outputSize,
                        utilizationModel, utilizationModel, utilizationModel
                );
                cloudlet.setUserId(broker.getId());
                // DO NOT set guestId here. The external scheduler will do this.
                // cloudlet.setGuestId(contain.getId()); // Remove this line
                cloudletList.add(cloudlet);
            }
            Log.printLine("Created " + cloudletList.size() + " Cloudlets.");


            // submit cloudlet list to the broker
            broker.submitCloudletList(cloudletList);

            // Starts the simulation
            CloudSim.startSimulation();

            CloudSim.stopSimulation();

            // Print results when simulation is over
            List<Cloudlet> newList = broker.getCloudletReceivedList();
            printCloudletList(newList);

            Log.printLine("CloudSimExample1 finished!");
            broker.sendResetRequestToControlPlane();

        }
        catch(Exception e){
            e.printStackTrace();
        }
    }


    private static Datacenter createDatacenter(String name) {
        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double time_zone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        LinkedList<Storage> storageList = new LinkedList<>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        Datacenter datacenter = null;
        try {
            // Pass the hostList to the Datacenter constructor
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }

    private static Live_Kubernetes_Broker createBroker() {
        Live_Kubernetes_Broker broker = null;
        try {
            broker = new Live_Kubernetes_Broker("Broker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    private static void printCloudletList(List<Cloudlet> list) {
        int size = list.size();
        Cloudlet cloudlet;

        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + "Time" + indent
                + "Start Time" + indent + "Finish Time");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet value : list) {
            cloudlet = value;
            Log.print(indent + cloudlet.getCloudletId() + indent + indent);

            if (cloudlet.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.print("SUCCESS");

                Log.println(indent + indent + cloudlet.getResourceId()
                        + indent + indent + indent + cloudlet.getGuestId()
                        + indent + indent
                        + dft.format(cloudlet.getActualCPUTime()) + indent
                        + indent + dft.format(cloudlet.getExecStartTime())
                        + indent + indent
                        + dft.format(cloudlet.getExecFinishTime()));
            } else {
                Log.print("FAILED"); // Add this to see failed cloudlets
                Log.println(indent + indent + cloudlet.getResourceId()
                        + indent + indent + indent + cloudlet.getGuestId()
                        + indent + indent + "N/A" + indent + indent + "N/A" + indent + indent + "N/A");
            }
        }
    }
}
