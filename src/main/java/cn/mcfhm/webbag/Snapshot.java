package cn.mcfhm.webbag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;

/**
 * 把玩家背包序列化为网页用的 JSON。
 * 槽位编号与 Bukkit 的 PlayerInventory 一致：0-8 快捷栏，9-35 主背包，36 头盔，37 胸甲，38 护腿，39 靴子，40 副手。
 */
final class Snapshot {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Snapshot() {
    }

    static String build(Player player, boolean ender) {
        Json json = new Json(16 * 1024);
        json.beginObject();
        json.name("ok").value(true);
        json.name("type").value(ender ? "ender" : "inv");
        json.name("time").value(System.currentTimeMillis());

        json.name("player").beginObject();
        json.name("name").value(player.getName());
        json.name("uuid").value(player.getUniqueId().toString());
        json.name("world").value(player.getWorld().getName());
        json.name("x").value(player.getLocation().getBlockX());
        json.name("y").value(player.getLocation().getBlockY());
        json.name("z").value(player.getLocation().getBlockZ());
        json.name("health").value(player.getHealth());
        json.name("maxHealth").value(maxHealth(player));
        json.name("food").value(player.getFoodLevel());
        json.name("level").value(player.getLevel());
        json.name("mode").value(player.getGameMode().name());
        json.name("armor").value(armorValue(player));
        json.endObject();

        json.name("slots").beginArray();
        if (ender) {
            ItemStack[] contents = player.getEnderChest().getContents();
            for (int slot = 0; slot < contents.length; slot++) {
                writeItem(json, contents[slot], slot);
            }
        } else {
            for (int slot = 0; slot <= 40; slot++) {
                ItemStack item = player.getInventory().getItem(slot);
                writeItem(json, item, slot);
            }
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    /** 管理指令 selftest 用：不依赖在线玩家，验证 JSON 生成链路。 */
    static String sample() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("测试利刃"));
        meta.lore(List.of(Component.text("第一行说明"), Component.text("第二行说明")));
        if (meta instanceof Damageable damageable) {
            damageable.setDamage(120);
        }
        sword.setItemMeta(meta);

        ItemStack planks = new ItemStack(Material.OAK_PLANKS, 32);
        ItemStack[] slots = new ItemStack[41];
        slots[0] = sword;
        slots[9] = planks;

        Json json = new Json(4096);
        json.beginObject();
        json.name("ok").value(true);
        json.name("type").value("inv");
        json.name("time").value(System.currentTimeMillis());
        json.name("player").beginObject();
        json.name("name").value("SelfTest");
        json.name("uuid").value("00000000-0000-0000-0000-000000000000");
        json.name("world").value("world");
        json.name("x").value(0);
        json.name("y").value(64);
        json.name("z").value(0);
        json.name("health").value(20.0);
        json.name("maxHealth").value(20.0);
        json.name("food").value(20);
        json.name("level").value(30);
        json.name("mode").value("SURVIVAL");
        json.name("armor").value(0.0);
        json.endObject();
        json.name("slots").beginArray();
        for (int slot = 0; slot < slots.length; slot++) {
            writeItem(json, slots[slot], slot);
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    private static void writeItem(Json json, ItemStack item, int slot) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return;
        }
        Material material = item.getType();
        ItemMeta meta = item.getItemMeta();

        json.beginObject();
        json.name("slot").value(slot);
        json.name("key").value(material.getKey().getKey());
        String icon = Textures.icon(material.getKey().getKey());
        if (icon != null) {
            json.name("icon").value(icon);
        }
        json.name("amount").value(item.getAmount());
        json.name("max").value(Math.max(1, material.getMaxStackSize()));
        json.name("name").value(displayName(meta));

        List<Component> lore = meta == null ? null : meta.lore();
        if (lore != null && !lore.isEmpty()) {
            json.name("lore").beginArray();
            for (Component line : lore) {
                json.value(PLAIN.serialize(line));
            }
            json.endArray();
        }

        if (meta != null && meta.hasEnchants()) {
            json.name("ench").beginArray();
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> e : meta.getEnchants().entrySet()) {
                json.beginObject();
                json.name("k").value(e.getKey().getKey().getKey());
                json.name("l").value(e.getValue());
                json.endObject();
            }
            json.endArray();
        }

        if (meta instanceof Damageable damageable) {
            int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : material.getMaxDurability();
            if (max > 1) {
                json.name("dura").value(damageable.getDamage());
                json.name("maxDura").value(max);
            }
        }

        if (meta != null && meta.hasCustomModelData()) {
            json.name("cmd").value(meta.getCustomModelData());
        }
        if (meta != null && meta.isUnbreakable()) {
            json.name("unbreakable").value(true);
        }
        json.endObject();
    }

    private static String displayName(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        Component component = meta.displayName();
        if (component == null && meta.hasItemName()) {
            component = meta.itemName();
        }
        if (component == null) {
            return null;
        }
        String text = PLAIN.serialize(component);
        return text.isBlank() ? null : text;
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return 20.0;
        }
        return attribute.getValue();
    }

    private static double armorValue(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ARMOR);
        return attribute == null ? 0.0 : attribute.getValue();
    }
}
