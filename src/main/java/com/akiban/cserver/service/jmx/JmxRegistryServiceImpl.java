package com.akiban.cserver.service.jmx;

import com.akiban.cserver.service.Service;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.*;

public class JmxRegistryServiceImpl implements JmxRegistryService, JmxManageable, Service {
    private static final String FORMATTER = "com.akiban:type=%s";

    private boolean started = false;
    private final Map<JmxManageable,ObjectName> serviceToName = new HashMap<JmxManageable, ObjectName>();
    private final Map<ObjectName,JmxManageable> nameToService = new HashMap<ObjectName,JmxManageable>();

    private final Object INTERNAL_LOCK = new Object();

    private void addService(ObjectName objectName, JmxManageable service) {
        assert Thread.holdsLock(INTERNAL_LOCK) : "this method must be called from a synchronized block";

        ObjectName oldName = serviceToName.put(service, objectName);
        JmxManageable oldService = nameToService.put(objectName, service);
        assert oldName == null : String.format("(%s) %s bumped %s", objectName, service, oldName);
        assert oldService == null : String.format("(%s) %s bumped %s", service, objectName, oldService);
    }

    private void removeService(ObjectName objectName) {
        assert Thread.holdsLock(INTERNAL_LOCK) : "this method must be called from a synchronized block";

        JmxManageable removedService = nameToService.remove(objectName);
        assert removedService != null : "removed a null JmxManageble";
        ObjectName removedName = serviceToName.remove(removedService);
        assert removedName.equals(objectName) : String.format("%s != %s", removedName, objectName);
    }

    @Override
    public void register(JmxManageable service) {
        final JmxObjectInfo info = service.getJmxObjectInfo();
        validate(info);
        String serviceName = info.getObjectName();
        if (!serviceName.matches("[\\w\\d]+")) {
            throw new JmxRegistrationException(service.getClass(), serviceName);
        }
        final ObjectName objectName;
        synchronized (INTERNAL_LOCK) {
            if (serviceToName.containsKey(service)) {
                throw new JmxRegistrationException("Already registered instance of " + service.getClass());
            }
            objectName = getObjectName(serviceName, nameToService.keySet());
            addService(objectName, service);

            if (started) {
                try {
                    getMBeanServer().registerMBean(info.getInstance(), objectName);
                }
                catch (Exception e) {
                    removeService(objectName);
                    throw new JmxRegistrationException(e);
                }
            }
        }
    }

    protected MBeanServer getMBeanServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }

    /**
     * Returns a unique object name. This method is <em>NOT</em> thread-safe; it's up to the caller to
     * provide any locking for consistency, atomicity, etc. Note that this method does not add the ObjectName
     * it generates to the set.
     * @param serviceName the service name
     * @param uniquenessSet the set that defines existing ObjectNames
     * @return an ObjectName that is not in the given set.
     */
    private ObjectName getObjectName(String serviceName, Set<ObjectName> uniquenessSet) {
        assert Thread.holdsLock(INTERNAL_LOCK) : "this method must be called from a synchronized block";

        ObjectName objectName;
        try {
            objectName = new ObjectName(String.format(FORMATTER, serviceName));
            if (uniquenessSet.contains(objectName)) {
                throw new JmxRegistrationException("Bean name conflict: " + serviceName);
            }
        } catch (MalformedObjectNameException e) {
            throw new JmxRegistrationException(e);
        }
        return objectName;
    }

    @Override
    public void unregister(String objectNameString) {
        synchronized (INTERNAL_LOCK) {
            try {
                final ObjectName registeredObject = new ObjectName(String.format(FORMATTER, objectNameString));
                if (started) {
                    getMBeanServer().unregisterMBean(registeredObject);
                }
                removeService(registeredObject);
            } catch (Exception e) {
                throw new JmxRegistrationException(e);
            }
        }
    }

    @Override
    public void start() {
        final MBeanServer mbs = getMBeanServer();
        synchronized (INTERNAL_LOCK) {
            if (started) {
                return;
            }
            for (Map.Entry<JmxManageable,ObjectName> entry : serviceToName.entrySet()) {
                final JmxManageable service = entry.getKey();
                final ObjectName objectName = entry.getValue();
                try {
                    mbs.registerMBean(service.getJmxObjectInfo().getInstance(), objectName);
                } catch (Exception e) {
                    throw new JmxRegistrationException("for " + objectName, e);
                }
            }
            started = true;
        }
    }

    @Override
    public void stop() {
        final MBeanServer mbs = getMBeanServer();
        synchronized (INTERNAL_LOCK) {
            if (!started) {
                return;
            }
            for (ObjectName objectName : nameToService.keySet()) {
                try {
                    mbs.unregisterMBean(objectName);
                } catch (Exception e) {
                    throw new JmxRegistrationException(e);
                }
            }
            started = false;
        }
    }

    @Override
    public JmxObjectInfo getJmxObjectInfo() {
        return new JmxObjectInfo("JmxManager", this, JmxRegistryServiceMXBean.class);
    }

    private static boolean isManagable(Class<?> theInterface) {
        return theInterface.getSimpleName().endsWith("MXBean");
    }

    void validate(JmxObjectInfo objectInfo) {
        Class<?> jmxInterface = objectInfo.getManagedInterface();
        if (!isManagable(jmxInterface)) {
            throw new JmxRegistrationException("Managed interface must end in \"MXBean\"");
        }
        assert jmxInterface.isAssignableFrom(objectInfo.getInstance().getClass())
            : String.format("%s is not assignable from %s", jmxInterface, objectInfo.getInstance().getClass());
        Set<Class<?>> objectInterfaces = getAllInterfaces(objectInfo.getInstance().getClass());
        Iterator<Class<?>> interfacesIter = objectInterfaces.iterator();
        while (interfacesIter.hasNext()) {
            if (!isManagable(interfacesIter.next())) {
                interfacesIter.remove();
            }
        }
        if (objectInterfaces.size() != 1) {
            throw new JmxRegistrationException("Need exactly one *MXBean interface. Found: " + objectInterfaces);
        }
    }

    private static Set<Class<?>> getAllInterfaces(Class<?> root) {
        Set<Class<?>> set = new HashSet<Class<?>>();
        if (root.isInterface()) {
            set.add(root);
        }
        getAllInterfaces(root.getInterfaces(), set);
        return set;
    }

    private static void getAllInterfaces(Class<?>[] roots, Set<Class<?>> set) {
        for (Class<?> root : roots) {
            assert root.isInterface() : root;
            if (!set.contains(root)) {
                set.add(root);
                getAllInterfaces(root.getInterfaces(), set);
            }
        }
    }
}
