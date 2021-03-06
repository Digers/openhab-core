/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.thing.internal;

import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.test.java.JavaOSGiTest;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ManagedThingProvider;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.openhab.core.thing.link.ItemChannelLinkRegistry;
import org.openhab.core.thing.link.ThingLinkManager;
import org.openhab.core.thing.type.ChannelDefinitionBuilder;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeBuilder;
import org.openhab.core.thing.type.ChannelTypeProvider;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.type.ThingType;
import org.openhab.core.thing.type.ThingTypeBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.StateOption;
import org.osgi.service.component.ComponentContext;

/**
 *
 * These tests will check (un-)linking of items and channels managed
 * by {@link ThingLinkManager}.
 *
 * @author Alex Tugarev - Initial contribution
 * @author Dennis Nobel - Added test for bug 459628 (lifecycle problem)
 * @author Thomas Höfer - Thing type constructor modified because of thing properties introduction
 * @author Kai Kreuzer - Adapted to new service implementation
 * @author Wouter Born - Migrate tests from Groovy to Java
 */
public class ThingLinkManagerOSGiTest extends JavaOSGiTest {

    private ThingRegistry thingRegistry;
    private ManagedThingProvider managedThingProvider;
    private ItemRegistry itemRegistry;
    private ItemChannelLinkRegistry itemChannelLinkRegistry;

    private final Map<String, Object> context = new ConcurrentHashMap<>();

    @Before
    public void setup() {
        context.clear();

        registerVolatileStorageService();
        thingRegistry = getService(ThingRegistry.class);
        managedThingProvider = getService(ManagedThingProvider.class);
        itemRegistry = getService(ItemRegistry.class);
        assertNotNull(itemRegistry);
        itemChannelLinkRegistry = getService(ItemChannelLinkRegistry.class);
        assertThat(thingRegistry, is(notNullValue()));

        ComponentContext componentContext = mock(ComponentContext.class);
        when(componentContext.getBundleContext()).thenReturn(bundleContext);

        TestThingHandlerFactory thingHandlerFactory = new TestThingHandlerFactory();
        thingHandlerFactory.activate(componentContext);
        registerService(thingHandlerFactory, ThingHandlerFactory.class.getName());

        StateDescription state = new StateDescription(BigDecimal.valueOf(0), BigDecimal.valueOf(100),
                BigDecimal.valueOf(10), "%d Peek", true,
                Collections.singletonList(new StateOption("SOUND", "My great sound.")));

        ChannelType channelType = ChannelTypeBuilder.state(new ChannelTypeUID("hue:alarm"), "Alarm Channel", "Number")
                .withStateDescription(state).build();
        List<ChannelType> channelTypes = singletonList(channelType);

        ChannelTypeProvider channelTypeProvider = mock(ChannelTypeProvider.class);
        when(channelTypeProvider.getChannelTypes(nullable(Locale.class))).thenReturn(channelTypes);
        when(channelTypeProvider.getChannelType(any(ChannelTypeUID.class), nullable(Locale.class)))
                .then(new Answer<@Nullable ChannelType>() {
                    @Override
                    public @Nullable ChannelType answer(InvocationOnMock invocation) throws Throwable {
                        ChannelTypeUID uid = (ChannelTypeUID) invocation.getArgument(0);
                        return channelTypes.stream().filter(t -> t.getUID().equals(uid)).findFirst().get();

                    }
                });
        registerService(channelTypeProvider);

        ThingType thingType = ThingTypeBuilder.instance(new ThingTypeUID("hue:lamp"), "label")
                .withChannelDefinitions(singletonList(new ChannelDefinitionBuilder("1", channelType.getUID()).build()))
                .build();
        SimpleThingTypeProvider thingTypeProvider = new SimpleThingTypeProvider(singletonList(thingType));
        registerService(thingTypeProvider);
    }

    @After
    public void teardown() {
        ManagedThingProvider managedThingProvider = getService(ManagedThingProvider.class);
        if (managedThingProvider != null) {
            managedThingProvider.getAll().forEach(t -> managedThingProvider.remove(t.getUID()));
        }

        itemChannelLinkRegistry.getAll().forEach(l -> itemChannelLinkRegistry.remove(l.getUID()));
    }

    @Test
    public void assertThatLinksAreRemovedUponThingRemoval() {
        ThingUID thingUID = new ThingUID("hue:lamp:lamp1");
        Thing thing = thingRegistry.createThingOfType(new ThingTypeUID("hue:lamp"), thingUID, null, "test thing",
                new Configuration());

        List<Channel> channels = thing.getChannels();
        assertThat(channels.size(), is(1));
        Channel channel = channels.get(0);

        managedThingProvider.add(thing);
        waitForAssert(() -> assertThat(itemChannelLinkRegistry.getLinkedItems(channel.getUID()).size(), is(1)));

        managedThingProvider.remove(thingUID);
        waitForAssert(() -> assertThat(itemChannelLinkRegistry.getLinkedItems(channel.getUID()).size(), is(0)));
    }

    @Test
    public void assertThatChannelLinkedAndChannelUnlinkedAtThingHandlerIsCalled() {
        ThingUID thingUID = new ThingUID("hue:lamp:lamp1");
        Thing thing = thingRegistry.createThingOfType(new ThingTypeUID("hue:lamp"), thingUID, null, "test thing",
                new Configuration());
        if (thing != null) {
            managedThingProvider.add(thing);
        } else {
            throw new AssertionError("thing is null");
        }

        ChannelUID channelUID = new ChannelUID(thingUID, "1");

        waitForAssert(() -> {
            assertThat(context.remove("linkedChannel"), is(equalTo(channelUID)));
            assertThat(context.remove("unlinkedChannel"), is(nullValue()));
        });

        itemChannelLinkRegistry.removeLinksForThing(thingUID);

        waitForAssert(() -> assertThat(context.remove("unlinkedChannel"), is(equalTo(channelUID))));
    }

    class TestThingHandlerFactory extends BaseThingHandlerFactory {
        @Override
        public void activate(ComponentContext componentContext) {
            super.activate(componentContext);
        }

        @Override
        public boolean supportsThingType(ThingTypeUID thingTypeUID) {
            return true;
        }

        @Override
        protected ThingHandler createHandler(Thing thing) {
            return new TestThingHandler(thing);
        }
    }

    class TestThingHandler extends BaseThingHandler {
        public TestThingHandler(Thing thing) {
            super(thing);
        }

        @Override
        public void handleCommand(ChannelUID channelUID, Command command) {
        }

        @Override
        public void initialize() {
            updateStatus(ThingStatus.ONLINE);
        }

        @Override
        public void channelLinked(ChannelUID channelUID) {
            context.put("linkedChannel", channelUID);
        }

        @Override
        public void channelUnlinked(ChannelUID channelUID) {
            context.put("unlinkedChannel", channelUID);
        }
    }
}
