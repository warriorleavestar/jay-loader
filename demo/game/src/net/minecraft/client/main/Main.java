package net.minecraft.client.main;
import net.minecraft.core.Registry;
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("[game] 原版游戏启动");
        Registry.register("minecraft:stone", "石头 (原版)");
        int n = Integer.getInteger("game.classes", 0);
        long s = 0; for (int i = 0; i < n; i++) s += Class.forName("net.minecraft.gen.C" + i).getDeclaredMethods().length;
        Registry.freeze();
        System.out.println("[game] 加载了 " + n + " 个游戏类, 注册表冻结, 内容:");
        Registry.dump();
    }
}
