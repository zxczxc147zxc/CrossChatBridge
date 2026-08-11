package com.zxczxc147zxc.crosschat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.commands.CommandSourceStack;

import java.lang.reflect.Method;

public final class PermissionCompat {

    private static final int ADMIN_LEVEL = 4;

    private PermissionCompat() {}

    public static boolean isAdmin(CommandSourceStack source) {
        try {
            Class<?> permCls = Class.forName("net.minecraft.server.permissions.Permission");
            Object admin = null;
            for (String name : new String[]{"COMMANDS_ADMIN", "RESTRICTED_COMMAND", "COMMANDS_LEVEL_4", "ADMIN"}) {
                try {
                    admin = permCls.getField(name).get(null);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (admin != null) {
                Method permissionsMethod = source.getClass().getMethod("permissions");
                Object permSet = permissionsMethod.invoke(source);
                if (permSet != null) {
                    for (Method m : permSet.getClass().getMethods()) {
                        if ("hasPermission".equals(m.getName()) && m.getParameterCount() == 1) {
                            return (boolean) m.invoke(permSet, admin);
                        }
                    }
                }
            }
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
            String methodName = resolver.mapMethodName("official",
                    "net.minecraft.commands.CommandSourceStack", "hasPermission", "(I)Z");
            if (methodName == null) return false;
            return (boolean) source.getClass().getMethod(methodName, int.class)
                    .invoke(source, ADMIN_LEVEL);
        } catch (Throwable t) {
            return false;
        }
    }
}