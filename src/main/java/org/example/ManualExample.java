package org.example;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.core.VirtualEntity;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

public class ManualExample {

    /** The host list. */
    private static List<HostEntity> hostList = new ArrayList<>();
    /** The cloudlet list. */
    private static List<Cloudlet> cloudletList;
    /** The vmlist. */
    private static List<VirtualEntity> vmlist;
    /** the containerlist */
    private static List<GuestEntity> containerlist;

    // HOST Specifications
    private static int HOST_MIPS = 1000;
    private static int HOST_RAM = 8192;
    private static long HOST_STORAGE = 10000;
    private static int HOST_BW = 10000;
    private static int HOST_CORES = 4;

    // TEST settings
    private static int HOST_NUM = 3;

    public static void main(String[] args) {
        try{
            //Initiate CloudSim
            CloudSim.init(1,Calendar.getInstance(),false);

            //Get a broker
            DatacenterBroker broker = createBroker();

            //Make all hosts
            for(int i = 0; i < HOST_NUM; i++){

                //Make list of PEs for current host
                List<Pe> peList = new ArrayList<>();
                for(int j = 0; i < HOST_CORES; i++){
                    peList.add(new Pe(j, new PeProvisionerSimple(HOST_MIPS)));
                }

                //Make and add new host to the list
                hostList.add(new Host(
                        i,
                        new RamProvisionerSimple(HOST_RAM),
                        new BwProvisionerSimple(HOST_BW),
                        HOST_STORAGE,
                        peList,
                        new VmSchedulerSpaceShared(peList)
                ));
            }

            vmlist = new ArrayList<>();

            for (int i = 0; i < HOST_NUM; i++) {
                List<Pe> peList = new ArrayList<>();

                for (int j = 0; j < HOST_CORES; j++) {
                    // Give each core a share of the total host MIPS
                    peList.add(new Pe(j, new PeProvisionerSimple(HOST_MIPS / HOST_CORES)));
                }

                // Calculate total VM MIPS (same as host MIPS)
                double totalVmMips = peList.stream().mapToDouble(pe -> pe.getPeProvisioner().getMips()).sum();

                VirtualEntity vm = new Vm(
                        i, broker.getId(),
                        totalVmMips,          // VM uses all host MIPS
                        HOST_CORES,           // All cores
                        HOST_RAM,
                        HOST_BW,
                        10000,                // VM image size
                        "Xen",
                        new CloudletSchedulerTimeShared(),
                        new VmSchedulerTimeShared(peList),
                        new RamProvisionerSimple(HOST_RAM),
                        new BwProvisionerSimple(HOST_BW),
                        peList
                );

                vmlist.add(vm);
                hostList.add(vm); // Note: you probably meant to add the *Host* here, not the VM
            }



            // Create container
            containerlist = new ArrayList<>();
            GuestEntity container = new Container(3, broker.getId(), 100, 2, HOST_RAM/2, HOST_BW/2, 10000/2, "Docker",
                    new CloudletSchedulerTimeShared());
            containerlist.add(container);

            GuestEntity container2 = new Container(3, broker.getId(), 100, 2, HOST_RAM/2, HOST_BW/2, 10000/2, "Docker",
                    new CloudletSchedulerTimeShared());
            containerlist.add(container);

            // Create Datacenters
            createDatacenter("Datacenter_0");

            // submit vm and container list to the broker
            broker.submitGuestList(vmlist);
            broker.submitGuestList(containerlist);

            // Create Cloudlets
            cloudletList = new ArrayList<>();

            long length = 400000;
            long fileSize = 300;
            long outputSize = 300;
            UtilizationModel utilizationModel = new UtilizationModelFull();

            Cloudlet cloudlet = new Cloudlet(0, length, 2, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(broker.getId());
            cloudlet.setGuestId(3);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(1, length, 2, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(broker.getId());
            cloudlet.setGuestId(3);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(2, length, 2, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(broker.getId());
            cloudlet.setGuestId(4);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(3, length, 2, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(broker.getId());
            cloudlet.setGuestId(4);
            cloudletList.add(cloudlet);


            // submit cloudlet list to the broker
            broker.submitCloudletList(cloudletList);

            // Starts the simulation
            CloudSim.startSimulation();

            CloudSim.stopSimulation();

            // Print results when simulation is over
            List<Cloudlet> newList = broker.getCloudletReceivedList();
            printCloudletList(newList);

            Log.println("CloudSimExample1 finished!");

        }
        catch(Exception e){
            e.printStackTrace();
        }

    }


    private static Datacenter createDatacenter(String name) {
        // Create datacenter
        String arch = "x86"; // system architecture
        String os = "Linux"; // operating system
        String vmm = "Xen";
        double time_zone = 10.0; // time zone this resource located
        double cost = 3.0; // the cost of using processing in this resource
        double costPerMem = 0.05; // the cost of using memory in this resource
        double costPerStorage = 0.001; // the cost of using storage in this
        // resource
        double costPerBw = 0.0; // the cost of using bw in this resource
        LinkedList<Storage> storageList = new LinkedList<>(); // we are not adding SAN
        // devices by now

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    // We strongly encourage users to develop their own broker policies, to
    // submit vms and cloudlets according
    // to the specific rules of the simulated scenario
    /**
     * Creates the broker.
     *
     * @return the datacenter broker
     */
    private static DatacenterBroker createBroker() {
        DatacenterBroker broker = null;
        try {
            broker = new DatacenterBroker("Broker");
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
            }
        }
    }
}
