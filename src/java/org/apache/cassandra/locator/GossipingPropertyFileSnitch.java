/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.locator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import org.apache.cassandra.db.SystemTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.EndpointState;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.gms.IEndpointStateChangeSubscriber;
import org.apache.cassandra.gms.VersionedValue;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.service.StorageService;


public class GossipingPropertyFileSnitch extends AbstractNetworkTopologySnitch implements IEndpointStateChangeSubscriber
{
    private static final Logger logger = LoggerFactory.getLogger(GossipingPropertyFileSnitch.class);

    private PropertyFileSnitch psnitch;
    private String myDC;
    private String myRack;
    private Map<InetAddress, Map<String, String>> savedEndpoints;
    private String DEFAULT_DC = "UNKNOWN_DC";
    private String DEFAULT_RACK = "UNKNOWN_RACK";
    private boolean preferLocal;

    public GossipingPropertyFileSnitch() throws ConfigurationException
    {
        myDC = SnitchProperties.get("dc", null);
        myRack = SnitchProperties.get("rack", null);
        if (myDC == null || myRack == null)
            throw new ConfigurationException("DC or rack not found in snitch properties, check your configuration in: " + SnitchProperties.RACKDC_PROPERTY_FILENAME);

        myDC = myDC.trim();
        myRack = myRack.trim();
        preferLocal = Boolean.parseBoolean(SnitchProperties.get("preferLocal", "false"));
        try
        {
            psnitch = new PropertyFileSnitch();
            logger.info("Loaded " + PropertyFileSnitch.SNITCH_PROPERTIES_FILENAME + " for compatibility");
        }
        catch (ConfigurationException e)
        {
            logger.info("Unable to load " + PropertyFileSnitch.SNITCH_PROPERTIES_FILENAME + "; compatibility mode disabled");
        }
    }

    /**
     * Return the data center for which an endpoint resides in
     *
     * @param endpoint the endpoint to process
     * @return string of data center
     */
    public String getDatacenter(InetAddress endpoint)
    {
        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
            return myDC;

        EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(endpoint);
        if (epState == null || epState.getApplicationState(ApplicationState.DC) == null)
        {
            if (psnitch == null)
            {
                if (savedEndpoints == null)
                    savedEndpoints = SystemTable.loadDcRackInfo();
                if (savedEndpoints.containsKey(endpoint))
                    return savedEndpoints.get(endpoint).get("data_center");
                return DEFAULT_DC;
            }
            else
                return psnitch.getDatacenter(endpoint);
        }
        return epState.getApplicationState(ApplicationState.DC).value;
    }

    /**
     * Return the rack for which an endpoint resides in
     *
     * @param endpoint the endpoint to process
     * @return string of rack
     */
    public String getRack(InetAddress endpoint)
    {
        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
            return myRack;

        EndpointState epState = Gossiper.instance.getEndpointStateForEndpoint(endpoint);
        if (epState == null || epState.getApplicationState(ApplicationState.RACK) == null)
        {
            if (psnitch == null)
            {
                if (savedEndpoints == null)
                    savedEndpoints = SystemTable.loadDcRackInfo();
                if (savedEndpoints.containsKey(endpoint))
                    return savedEndpoints.get(endpoint).get("rack");
                return DEFAULT_RACK;
            }
            else
                return psnitch.getRack(endpoint);
        }
        return epState.getApplicationState(ApplicationState.RACK).value;
    }

    // IEndpointStateChangeSubscriber methods

    public void onJoin(InetAddress endpoint, EndpointState epState)
    {
        if (preferLocal && epState.getApplicationState(ApplicationState.INTERNAL_IP) != null)
            reConnect(endpoint, epState.getApplicationState(ApplicationState.INTERNAL_IP));
    }

    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value)
    {
        if (preferLocal && state == ApplicationState.INTERNAL_IP)
            reConnect(endpoint, value);
    }

    public void onAlive(InetAddress endpoint, EndpointState state)
    {
        if (preferLocal && state.getApplicationState(ApplicationState.INTERNAL_IP) != null)
            reConnect(endpoint, state.getApplicationState(ApplicationState.INTERNAL_IP));
    }

    public void onDead(InetAddress endpoint, EndpointState state)
    {
        // do nothing
    }

    public void onRestart(InetAddress endpoint, EndpointState state)
    {
        // do nothing
    }

    public void onRemove(InetAddress endpoint)
    {
        // do nothing.
    }

    private void reConnect(InetAddress endpoint, VersionedValue versionedValue)
    {
        if (!getDatacenter(endpoint).equals(myDC))
            return; // do nothing return back...

        try
        {
            InetAddress remoteIP = InetAddress.getByName(versionedValue.value);
            MessagingService.instance().getConnectionPool(endpoint).reset(remoteIP);
            logger.debug(String.format("Intiated reconnect to an Internal IP %s for the endpoint %s", remoteIP, endpoint));
        }
        catch (UnknownHostException e)
        {
            logger.error("Error in getting the IP address resolved", e);
        }
    }

    @Override
    public void gossiperStarting()
    {
        super.gossiperStarting();
        Gossiper.instance.addLocalApplicationState(ApplicationState.INTERNAL_IP,
                                                   StorageService.instance.valueFactory.internalIP(FBUtilities.getLocalAddress().getHostAddress()));
        Gossiper.instance.register(this);
    }
}
