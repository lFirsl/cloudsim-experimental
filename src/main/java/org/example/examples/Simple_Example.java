package org.example.examples;

/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation
 *               of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009, The University of Melbourne, Australia
 */

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.container.core.Container;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.core.VirtualEntity;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.Live_Kubernetes_Broker;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * A simple example showing the use of containers (ContainerCloudSim) and Vms (base CloudSim) in the same contexts.
 */
public class Simple_Example {

    /** The host list. */
    private static List<HostEntity> hostList = new ArrayList<>();

    /** The cloudlet list. */
    private static List<Cloudlet> cloudletList;

    /** The vmlist. */
    private static List<VirtualEntity> vmlist;

    /** the containerlist */
    private static List<GuestEntity> containerlist;

    public static void main(String[] args) {
        try{
            int num_users = 1;
            Calendar calendar = Calendar.getInstance();

            // Initiate CloudSim, first starting step
            CloudSim.init(num_users,calendar,false);

            // Then, create the broker - the thing that manages what gets assigned where
            Live_Kubernetes_Broker broker = createBroker();
            int brokerId = broker.getId();

            // Create host - this is the "actual" PC. We define the specs how we want. Handy!
            int mips = 1000; // Millions of Onstructions per second, was it?
            int ram = 8192; // Really, what PC has less than 8GB of RAM these days?
            long storage = 1000000; // host storage
            int bw = 10000;

            // PE stands for Processing Element - it is an abstraction of a CPU core. A CPU is made of a bundle of PEs
            List<Pe> peList = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                peList.add(new Pe(i, new PeProvisionerSimple(mips)));
            }

            //Here, we're adding hosts
            hostList.add(
                    new Host(0, new RamProvisionerSimple(ram), new BwProvisionerSimple(bw), storage, peList, new VmSchedulerSpaceShared(peList))
            );

            vmlist = new ArrayList<>(); // Prepare vmlist

            // VM requirements for what will be hosting containers on the host
            mips = 1000;
            long size = 10000; // image size (MB)
            ram = 512; // vm memory (MB)
            bw = 1000;
            int pesNumber = 2; // number of cpus
            String vmm = "Xen"; // VMM name

            // Like before, we make a list of cpu cores we want to use
            peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips/4)));
            peList.add(new Pe(1, new PeProvisionerSimple(mips/4)));
            //Then we make a VM using said cpu cores.
            VirtualEntity vm1 = new Vm(1, brokerId, (double) mips/2, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared(),
                    new VmSchedulerTimeShared(peList),
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    peList);
            vmlist.add(vm1);
            hostList.add(vm1);

            //And again
            peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(mips/4)));
            peList.add(new Pe(1, new PeProvisionerSimple(mips/4)));
            VirtualEntity vm3 = new Vm(2, brokerId, (double) mips /2, pesNumber, ram, bw, size, vmm, new CloudletSchedulerSpaceShared(),
                    new VmSchedulerTimeShared(peList),
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    peList);
            vmlist.add(vm3);
            hostList.add(vm3);


            // We then make the containers we want to simulate
            containerlist = new ArrayList<>();
            GuestEntity container = new Container(3, brokerId, 100, pesNumber, ram/2, bw/2, size/2, "Docker",
                    new CloudletSchedulerTimeShared());
            containerlist.add(container);

            GuestEntity container2 = new Container(4, brokerId, 100, pesNumber, ram/2, bw/2, size/2, "Docker",
                    new CloudletSchedulerSpaceShared());
            containerlist.add(container2);

            // Create Datacenters
            Datacenter datacenter = createDatacenter("Datacenter_0");

            // submit vm and container list to the broker
            broker.submitGuestList(vmlist);
            broker.submitGuestList(containerlist);


            // Create Cloudlets - that is, the actual tasks these VMs and Containers need to finish up.
            cloudletList = new ArrayList<>();

            long length = 400000;
            long fileSize = 300;
            long outputSize = 300;

            // Determine how resources are used by cloudlets - in this case, everything is used up to 100%
            UtilizationModel utilizationModel = new UtilizationModelFull();


            // Creating the actual cloudlets - some duplication here, but this is an example. That's fine.
            Cloudlet cloudlet = new Cloudlet(0, length, pesNumber, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlet.setGuestId(3);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(1, length, pesNumber, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlet.setGuestId(3);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(2, length, pesNumber, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlet.setGuestId(4);
            cloudletList.add(cloudlet);

            cloudlet = new Cloudlet(3, length, pesNumber, fileSize,
                    outputSize, utilizationModel, utilizationModel,
                    utilizationModel);
            cloudlet.setUserId(brokerId);
            cloudlet.setGuestId(4);
            cloudletList.add(cloudlet);


            // Now we have everything! Just need to start the whole thing


            // submit cloudlet list to the broker
            broker.submitCloudletList(cloudletList);

            // Starts the simulation
            CloudSim.startSimulation();

            CloudSim.stopSimulation();


            // Print results when simulation is over
            List<Cloudlet> newList = broker.getCloudletReceivedList();
            printCloudletList(newList);

            Log.println("CloudSimExample1 finished!");
            broker.sendResetRequestToControlPlane();

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
            }
        }
    }
}
