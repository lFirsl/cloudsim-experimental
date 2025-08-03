package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;

import java.util.List;

public class VmAllocationPolicySimpleCustom extends VmAllocationPolicySimple {

    /**
     * Creates a new VmAllocationPolicySimple object.
     *
     * @param list the list of hosts
     * @pre $none
     * @post $none
     */
    public VmAllocationPolicySimpleCustom(List<? extends HostEntity> list) {
        super(list);
    }

    @Override
    public void deallocateHostForGuest(GuestEntity guest) {
        //We do NOT want to be deallocationGuests
        return;
    }
}
