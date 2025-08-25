package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.power.PowerVm;

import java.util.ArrayList;
import java.util.List;

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


    // Vm.java (or PowerVm.java if you prefer)
    @Override
    public double getCurrentRequestedTotalMips() {
        if (isBeingInstantiated()) return getMips() * getNumberOfPes();

        final double time = CloudSim.clock();
        final double perPeMips = getMips();
        double demand = 0.0;

        for (Cloudlet cl : getCloudletScheduler().getCloudletExecList()) {
            // UtilizationModelFull -> 1.0; others may be <1
            demand += cl.getUtilizationOfCpu(time) * cl.getNumberOfPes() * perPeMips;
        }

        // If you use nested guests, include them too:
        for (GuestEntity guest : getGuestList()) {
            demand += guest.getCurrentRequestedTotalMips();
        }

        double cap = getMips() * getNumberOfPes();
        return Math.min(demand, cap);
    }

    @Override
    public List<Double> getCurrentRequestedMips() {
        if (isBeingInstantiated()) {
            List<Double> l = new ArrayList<>(getNumberOfPes());
            for (int i = 0; i < getNumberOfPes(); i++) l.add(getMips());
            return l;
        }

        final double totalDemand = getCurrentRequestedTotalMips();
        final double perPeMips = getMips();
        final double peDemand = totalDemand / perPeMips; // "PEs worth" of demand

        final int full = (int)Math.floor(Math.min(peDemand, getNumberOfPes()));
        final double frac = Math.max(0.0, Math.min(1.0, peDemand - full));

        List<Double> req = new ArrayList<>(getNumberOfPes());
        for (int i = 0; i < full; i++) req.add(perPeMips);           // full PEs
        if (full < getNumberOfPes() && frac > 0) req.add(frac * perPeMips); // one fractional PE
        while (req.size() < getNumberOfPes()) req.add(0.0);          // pad with zeros

        // Optionally merge nested guests’ per-PE requests (if applicable)
        for (GuestEntity guest : getGuestList()) {
            req.addAll(guest.getCurrentRequestedMips());
        }
        return req;
    }




}
