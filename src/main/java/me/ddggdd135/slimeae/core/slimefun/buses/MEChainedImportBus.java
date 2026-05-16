package me.ddggdd135.slimeae.core.slimefun.buses;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import javax.annotation.Nonnull;
import me.ddggdd135.slimeae.api.abstracts.BusTickContext;
import me.ddggdd135.slimeae.api.abstracts.MEChainedBus;
import me.ddggdd135.slimeae.api.operations.ImportOperation;
import me.ddggdd135.slimeae.core.NetworkInfo;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

public class MEChainedImportBus extends MEChainedBus {

    @Override
    public boolean isSynchronized() {
        return false;
    }

    public MEChainedImportBus(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public void onNetworkUpdate(Block block, NetworkInfo networkInfo) {}

    // onNetworkTimeConsumingTick 走父类 MEChainedBus 默认实现:
    // 直接在 NetworkTimeConsumingTask 的工作线程上构造 context 并调用 onMEBusTick.
    // ImportOperation 内部持网络锁保证并发安全.

    @Override
    public void onMEBusTick(
            @Nonnull Block block, @Nonnull SlimefunItem item, @Nonnull SlimefunBlockData data, BusTickContext context) {
        ImportOperation.executeChained(context, true, false);
    }
}
