/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.sal.restconf.impl.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.FileNotFoundException;
import java.util.Set;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opendaylight.controller.sal.rest.impl.XmlToCompositeNodeProvider;
import org.opendaylight.controller.sal.restconf.impl.BrokerFacade;
import org.opendaylight.controller.sal.restconf.impl.ControllerContext;
import org.opendaylight.controller.sal.restconf.impl.RestconfImpl;
import org.opendaylight.yangtools.yang.data.api.CompositeNode;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

/**
 * @See {@link InvokeRpcMethodTest}
 *
 */
public class RestconfImplTest {

    private RestconfImpl restconfImpl = null;
    private static ControllerContext controllerContext = null;

    @BeforeClass
    public static void init() throws FileNotFoundException {
        Set<Module> allModules = TestUtils.loadModulesFrom("/full-versions/yangs");
        assertNotNull(allModules);
        SchemaContext schemaContext = TestUtils.loadSchemaContext(allModules);
        controllerContext = spy(ControllerContext.getInstance());
        controllerContext.setSchemas(schemaContext);

    }

    @Before
    public void initMethod() {
        restconfImpl = RestconfImpl.getInstance();
        restconfImpl.setControllerContext(controllerContext);
    }

    @Test
    public void testExample() throws FileNotFoundException {
        CompositeNode loadedCompositeNode = TestUtils.readInputToCnSn("/parts/ietf-interfaces_interfaces.xml",
                XmlToCompositeNodeProvider.INSTANCE);
        BrokerFacade brokerFacade = mock(BrokerFacade.class);
        when(brokerFacade.readOperationalData(any(YangInstanceIdentifier.class))).thenReturn(loadedCompositeNode);
        assertEquals(loadedCompositeNode, brokerFacade.readOperationalData(null));
    }

}
