package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.power.PowerVm;

public class PowerVmCustom extends PowerVm {

    private final int preferredHostId;

    public PowerVmCustom(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            int priority,
            String vmm,
            CloudletScheduler cloudletScheduler,
            double schedulingInterval,
            int preferredHostId
    ) {
        super(id, userId, mips, numberOfPes, ram, bw, size, priority, vmm, cloudletScheduler,schedulingInterval);
        this.preferredHostId = preferredHostId;
    }

    public int getPreferredHostId() {
        return preferredHostId;
    }
}
