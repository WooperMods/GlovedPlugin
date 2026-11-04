package dev.gloved.glovedplugin.lootr.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class InventorySerializer {
    public static byte[] serialize(Inventory inv) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(inv.getSize());
            for (int i = 0; i < inv.getSize(); i++) {
                try {
                    out.writeObject(inv.getItem(i));
                } catch (IOException e) {
                    // Skip problematic item
                    out.writeObject(null);
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static ItemStack[] deserialize(byte[] data) {
        if (data == null || data.length == 0) return new ItemStack[0];
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             BukkitObjectInputStream in = new BukkitObjectInputStream(bis)) {
            int size = in.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                try {
                    items[i] = (ItemStack) in.readObject();
                } catch (Exception e) {
                    items[i] = null;
                }
            }
            return items;
        } catch (Exception e) {
            return new ItemStack[0];
        }
    }
}
