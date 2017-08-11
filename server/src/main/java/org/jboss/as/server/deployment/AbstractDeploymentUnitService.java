/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.deployment;

import org.jboss.as.server.logging.ServerLogger;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StabilityMonitor;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Abstract service responsible for managing the life-cycle of a {@link DeploymentUnit}.
 *
 * @author John Bailey
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public abstract class AbstractDeploymentUnitService implements Service<DeploymentUnit> {

    private static final String FIRST_PHASE_NAME = Phase.values()[0].name();
    private final InjectedValue<DeployerChains> deployerChainsInjector = new InjectedValue<DeployerChains>();

    private volatile DeploymentUnitPhaseBuilder phaseBuilder = null;
    private volatile DeploymentUnit deploymentUnit;
    private volatile StabilityMonitor monitor;

    protected AbstractDeploymentUnitService() {
    }

    public synchronized void start(final StartContext context) throws StartException {
        ServiceTarget target = context.getChildTarget();
        final String deploymentName = context.getController().getName().getSimpleName();
        monitor = new StabilityMonitor();
        monitor.addController(context.getController());
        // Create the first phase deployer
        deploymentUnit = createAndInitializeDeploymentUnit(context.getController().getServiceContainer());

        final String managementName = deploymentUnit.getAttachment(Attachments.MANAGEMENT_NAME);
        if (deploymentUnit.getParent()==null) {
            ServerLogger.DEPLOYMENT_LOGGER.startingDeployment(managementName, deploymentName);
        } else {
            ServerLogger.DEPLOYMENT_LOGGER.startingSubDeployment(deploymentName);
        }

        Consumer<StartContext> installer = startContext -> {
            final ServiceName serviceName = deploymentUnit.getServiceName().append(FIRST_PHASE_NAME);
            final Phase firstPhase = Phase.values()[0];
            final DeploymentUnitPhaseService<?> phaseService = DeploymentUnitPhaseService.create(deploymentUnit, firstPhase);
            final ServiceBuilder<?> phaseServiceBuilder = startContext.getChildTarget().addService(serviceName, phaseService);
            phaseServiceBuilder.addDependency(Services.JBOSS_DEPLOYMENT_CHAINS, DeployerChains.class, phaseService.getDeployerChainsInjector());
            phaseServiceBuilder.install();
        };

        // If a builder was previously attached, reattach to the new deployment unit instance and build the initial phase using that builder
        if (this.phaseBuilder != null) {
            this.deploymentUnit.putAttachment(Attachments.DEPLOYMENT_UNIT_PHASE_BUILDER, this.phaseBuilder);
            Collection<AttachmentKey<?>> initialAttachmentKeys = this.getDeploymentUnitAttachmentKeys();
            Consumer<StopContext> uninstaller = stopContext -> {
                // Cleanup any deployment unit attachments that were not properly removed during DUP undeploy
                this.getDeploymentUnitAttachmentKeys().stream()
                        .filter(key -> !initialAttachmentKeys.contains(key))
                        .forEach(key -> this.deploymentUnit.removeAttachment(key));
            };

            ServiceName serviceName = this.deploymentUnit.getServiceName().append("installer");
            this.phaseBuilder.build(target, serviceName, new AbstractService<Object>() {
                @Override
                public void start(StartContext context) throws StartException {
                    installer.accept(context);
                }

                @Override
                public void stop(StopContext context) {
                    uninstaller.accept(context);
                }
            }).install();
        } else {
            installer.accept(context);
        }
    }

    /**
     * Template method required for implementations to create and fully initialize a deployment unit instance.  This method
     * should be used to attach any initial deployment unit attachments required for the deployment type.
     *
     * @param registry The service registry
     * @return An initialized DeploymentUnit instance
     */
    protected abstract DeploymentUnit createAndInitializeDeploymentUnit(final ServiceRegistry registry);

    public synchronized void stop(final StopContext context) {
        final String deploymentName = context.getController().getName().getSimpleName();
        final String managementName = deploymentUnit.getAttachment(Attachments.MANAGEMENT_NAME);
        if (deploymentUnit.getParent()==null) {
            ServerLogger.DEPLOYMENT_LOGGER.stoppedDeployment(managementName, deploymentName, (int) (context.getElapsedTime() / 1000000L));
        } else {
            ServerLogger.DEPLOYMENT_LOGGER.stoppedSubDeployment(deploymentName, (int) (context.getElapsedTime() / 1000000L));
        }
        // Retain any attached builder across restarts
        phaseBuilder = deploymentUnit.getAttachment(Attachments.DEPLOYMENT_UNIT_PHASE_BUILDER);
        // clear up all attachments
        getDeploymentUnitAttachmentKeys().forEach(key -> deploymentUnit.removeAttachment(key));
        deploymentUnit = null;
        monitor.removeController(context.getController());
        monitor = null;
    }

    /**
     * Returns a new set containing the keys of all current deployment unit attachments.
     *
     */
    private Collection<AttachmentKey<?>> getDeploymentUnitAttachmentKeys() {
        return ((SimpleAttachable) this.deploymentUnit).attachmentKeys();
    }

    public synchronized DeploymentUnit getValue() throws IllegalStateException, IllegalArgumentException {
        return deploymentUnit;
    }

    public DeploymentStatus getStatus() {
        StabilityMonitor monitor = this.monitor;
        if (monitor == null) {
            return DeploymentStatus.STOPPED;
        }
        final Set<ServiceController<?>> problems = new HashSet<ServiceController<?>>();
        try {
            monitor.awaitStability(problems, problems);
        } catch (final InterruptedException e) {
            // ignore
        }
        return problems.isEmpty() ? DeploymentStatus.OK : DeploymentStatus.FAILED;
    }

    Injector<DeployerChains> getDeployerChainsInjector() {
        return deployerChainsInjector;
    }

    public enum DeploymentStatus {
        NEW,
        OK,
        FAILED,
        STOPPED
    }
}
