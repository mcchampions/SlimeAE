package me.ddggdd135.slimeae.core.slimefun.terminals;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.libraries.dough.inventory.InvUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import me.ddggdd135.guguslimefunlib.items.ItemKey;
import me.ddggdd135.slimeae.SlimeAEPlugin;
import me.ddggdd135.slimeae.api.autocraft.CraftingRecipe;
import me.ddggdd135.slimeae.api.interfaces.IRecipeCompletableWithGuide;
import me.ddggdd135.slimeae.api.interfaces.IStorage;
import me.ddggdd135.slimeae.api.items.ItemRequest;
import me.ddggdd135.slimeae.core.NetworkInfo;
import me.ddggdd135.slimeae.core.items.MenuItems;
import me.ddggdd135.slimeae.utils.ItemUtils;
import me.ddggdd135.slimeae.utils.RecipeUtils;
import org.bukkit.Location;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class MECraftingTerminal extends METerminal implements IRecipeCompletableWithGuide {

    /**
     * 每个合成终端方块一份的配方匹配缓存.
     * <p>核心思路:
     * <ul>
     *   <li>{@code matchedRecipe} 缓存上次成功匹配的 {@link CraftingRecipe}.</li>
     *   <li>每次 {@link #matchRecipe(Block)} 优先做廉价的 revalidation: 当前 9 个合成槽和上次匹配的配方
     *       是否仍然兼容(Material+meta 一致, 数量足够). 兼容则直接返回缓存, 跳过 {@link RecipeUtils#getRecipe} 对几百条配方的扫描.</li>
     *   <li>{@code lastDrawnRecipeIdentity} 用来去重 GUI 重绘: 如果 tick 时匹配到的还是同一条配方,
     *       就不再调 {@code replaceExistingItem} 写 BlockMenu.</li>
     * </ul>
     * 不缓存 fingerprint by amount, 因为合成过程中槽位数量会变, 但配方仍然适用.
     */
    private static final class RecipeMatchCache {
        volatile CraftingRecipe matchedRecipe;
        volatile Object lastDrawnRecipeIdentity;
    }

    private static final ConcurrentHashMap<Location, RecipeMatchCache> RECIPE_CACHE = new ConcurrentHashMap<>();

    private static final Object DRAWN_EMPTY = new Object();

    public MECraftingTerminal(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public int[] getBorderSlots() {
        return new int[] {0, 1, 3, 4, 5, 14, 23, 32, 33, 34, 35, 41, 42, 44, 45, 47, 49, 50, 51, 52, 53};
    }

    @Override
    public int[] getDisplaySlots() {
        return new int[] {9, 10, 11, 12, 13, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 36, 37, 38, 39, 40};
    }

    @Override
    public int getInputSlot() {
        return 2;
    }

    @Override
    public int getChangeSort() {
        return 47;
    }

    @Override
    public int getFilter() {
        return 45;
    }

    @Override
    public int getPagePrevious() {
        return 46;
    }

    @Override
    public int getPageNext() {
        return 48;
    }

    public int[] getCraftSlots() {
        return new int[] {6, 7, 8, 15, 16, 17, 24, 25, 26};
    }

    public int getCraftOutputSlot() {
        return 43;
    }

    public int getReturnItemSlot() {
        return 52;
    }

    @Override
    @OverridingMethodsMustInvokeSuper
    public void init(@Nonnull BlockMenuPreset preset) {
        super.init(preset);
        preset.addItem(getReturnItemSlot(), MenuItems.PUSH_BACK);
    }

    @Override
    @OverridingMethodsMustInvokeSuper
    public void newInstance(@Nonnull BlockMenu blockMenu, @Nonnull Block block) {
        super.newInstance(blockMenu, block);
        blockMenu.replaceExistingItem(getCraftOutputSlot(), MenuItems.EMPTY);
        blockMenu.addMenuClickHandler(getCraftOutputSlot(), new ChestMenu.AdvancedMenuClickHandler() {
            @Override
            public boolean onClick(
                    InventoryClickEvent inventoryClickEvent,
                    Player player,
                    int i,
                    ItemStack cursor,
                    ClickAction clickAction) {
                NetworkInfo info = SlimeAEPlugin.getNetworkData().getNetworkInfo(block.getLocation());
                if (info == null) return false;
                ItemStack matched = matchItem(block);
                if (matched == null || matched.getType().isAir()) return false;
                if (cursor.getType().isAir() || SlimefunUtils.isItemSimilar(matched, cursor, true, false)) {
                    if (inventoryClickEvent.isShiftClick()) {
                        Inventory playerInventory = player.getInventory();
                        int count = 0;
                        while (matched != null
                                && !matched.getType().isAir()
                                && InvUtils.fits(
                                        playerInventory,
                                        matched,
                                        IntStream.range(0, playerInventory.getSize())
                                                .toArray())
                                && count < 64) {
                            doCraft(block);
                            playerInventory.addItem(matched);
                            matched = matchItem(block);
                            count++;
                        }
                        updateCraftingGui(block);
                    } else if (inventoryClickEvent.isLeftClick()
                            && cursor.getAmount() + matched.getAmount() <= matched.getMaxStackSize()) {
                        if (cursor.getType().isAir()
                                || (SlimefunUtils.isItemSimilar(matched, cursor, true, false)
                                        && cursor.getAmount() + matched.getAmount() <= cursor.getMaxStackSize())) {
                            ItemStack newCursor = matched.clone();
                            newCursor.add(cursor.getAmount());
                            player.setItemOnCursor(newCursor);
                            doCraft(block);
                            updateCraftingGui(block);
                        }
                    }
                }
                return false;
            }

            @Override
            public boolean onClick(Player player, int i, ItemStack itemStack, ClickAction clickAction) {
                return false;
            }
        });
        blockMenu.addMenuClickHandler(getReturnItemSlot(), (player, i, itemStack, clickAction) -> {
            NetworkInfo info = SlimeAEPlugin.getNetworkData().getNetworkInfo(block.getLocation());
            if (info == null) return false;
            IStorage networkStorage = info.getStorage();
            for (int slot : getCraftSlots()) {
                ItemStack item = blockMenu.getItemInSlot(slot);
                if (item != null && !item.getType().isAir()) networkStorage.pushItem(item);
            }
            return false;
        });

        addJEGRecipeButton(blockMenu, getJEGRecipeButtonSlot());
    }

    @Override
    @OverridingMethodsMustInvokeSuper
    protected void tick(@Nonnull Block block, @Nonnull SlimefunItem item, @Nonnull SlimefunBlockData data) {
        super.tick(block, item, data);
        BlockMenu blockMenu = StorageCacheUtils.getMenu(block.getLocation());
        if (blockMenu == null) return;
        if (blockMenu.hasViewer()) updateCraftingGui(block);
    }

    public void updateCraftingGui(@Nonnull Block block) {
        BlockMenu inv = StorageCacheUtils.getMenu(block.getLocation());
        if (inv == null) return;
        CraftingRecipe recipe = matchRecipe(block);
        RecipeMatchCache cache = RECIPE_CACHE.computeIfAbsent(block.getLocation(), loc -> new RecipeMatchCache());

        if (recipe == null || recipe.getOutput().length != 1) {
            if (cache.lastDrawnRecipeIdentity == DRAWN_EMPTY) return;
            inv.replaceExistingItem(getCraftOutputSlot(), MenuItems.EMPTY);
            cache.lastDrawnRecipeIdentity = DRAWN_EMPTY;
            return;
        }

        // 廉价 identity 比较: 同一个 CraftingRecipe 引用就不再重绘
        // (matchRecipe 命中 revalidation 时会返回上次同一个对象引用)
        if (cache.lastDrawnRecipeIdentity == recipe) return;

        ItemStack matched = recipe.getOutput()[0].clone();
        inv.replaceExistingItem(getCraftOutputSlot(), ItemUtils.createDisplayItem(matched, matched.getAmount(), false));
        cache.lastDrawnRecipeIdentity = recipe;
    }

    public void doCraft(@Nonnull Block block) {
        BlockMenu blockMenu = StorageCacheUtils.getMenu(block.getLocation());
        if (blockMenu == null) return;
        NetworkInfo info = SlimeAEPlugin.getNetworkData().getNetworkInfo(block.getLocation());
        if (info == null) return;
        CraftingRecipe recipe = matchRecipe(block);
        if (recipe == null) return;
        IStorage networkStorage = info.getStorage();
        ItemStack[] input = recipe.getInput();

        // 网络锁保证从 storage 取材料的整段流程不与多线程 bus/import 操作并发, 避免刷物.
        info.getStorageLock().lock();
        try {
            for (int i = 0; i < getCraftSlots().length; i++) {
                ItemStack itemStack = blockMenu.getItemInSlot(getCraftSlots()[i]);
                if (itemStack == null || itemStack.getType().isAir()) continue;
                if (input.length > i) itemStack.setAmount(itemStack.getAmount() - input[i].getAmount());
                else {
                    itemStack.setAmount(itemStack.getAmount() - 1);
                    continue;
                }
                if (itemStack.getAmount() == 0) {
                    ItemStack[] gotten = networkStorage
                            .takeItem(new ItemRequest(new ItemKey(input[i]), input[i].getAmount()))
                            .toItemStacks();
                    if (gotten.length != 0) itemStack.setAmount(gotten[0].getAmount());
                }
            }
        } finally {
            info.getStorageLock().unlock();
        }
    }

    @Nullable public ItemStack matchItem(@Nonnull Block block) {
        CraftingRecipe recipe = matchRecipe(block);

        if (recipe == null || recipe.getOutput().length != 1) return null;
        return recipe.getOutput()[0].clone();
    }

    public CraftingRecipe matchRecipe(@Nonnull Block block) {
        BlockMenu inv = StorageCacheUtils.getMenu(block.getLocation());
        if (inv == null) return null;

        RecipeMatchCache cache = RECIPE_CACHE.computeIfAbsent(block.getLocation(), loc -> new RecipeMatchCache());

        // 快路径: 上次匹配过的配方依然和当前 9 个合成槽兼容 -> 直接返回, 跳过全配方表扫描.
        // 这一招对 shift-click 循环至关重要 -- 64 次迭代里只有第一次走慢路径.
        CraftingRecipe last = cache.matchedRecipe;
        if (last != null && stillSatisfiesRecipe(inv, last)) {
            return last;
        }

        // 慢路径: 全配方表搜索.
        List<ItemStack> inputList = new ArrayList<>(getCraftSlots().length);
        for (int slot : getCraftSlots()) {
            inputList.add(inv.getItemInSlot(slot));
        }
        ItemStack[] inputs = inputList.toArray(ItemStack[]::new);

        CraftingRecipe recipe = RecipeUtils.getRecipe(inputs, RecipeUtils.CRAFTING_TABLE_TYPES);
        cache.matchedRecipe = recipe;
        return recipe;
    }

    /**
     * 廉价 revalidation: 给定一条已知配方, 检测当前合成槽是否仍然满足它.
     * O(9) 而不是 O(配方总数 * 9).
     */
    private boolean stillSatisfiesRecipe(@Nonnull BlockMenu inv, @Nonnull CraftingRecipe recipe) {
        ItemStack[] recipeInput = recipe.getInput();
        int[] slots = getCraftSlots();
        int n = Math.max(slots.length, recipeInput == null ? 0 : recipeInput.length);

        for (int i = 0; i < n; i++) {
            ItemStack slotItem = i < slots.length ? inv.getItemInSlot(slots[i]) : null;
            ItemStack expected = (recipeInput != null && i < recipeInput.length) ? recipeInput[i] : null;

            boolean slotEmpty = slotItem == null || slotItem.getType().isAir();
            boolean expectedEmpty = expected == null || expected.getType().isAir();

            if (slotEmpty != expectedEmpty) return false;
            if (slotEmpty) continue;

            // checkLore=true, checkAmount=true: SlimefunUtils 的语义是 slotItem.getAmount() >= expected.getAmount(),
            // 所以 shift-click 过程中 64->63->62 仍然算"足够".
            if (!SlimefunUtils.isItemSimilar(slotItem, expected, true, true)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {

            @Override
            public void onBlockBreak(@Nonnull Block b) {
                RECIPE_CACHE.remove(b.getLocation());
                BlockMenu blockMenu = StorageCacheUtils.getMenu(b.getLocation());

                if (blockMenu != null) {
                    blockMenu.dropItems(b.getLocation(), getInputSlot());
                    blockMenu.dropItems(b.getLocation(), getCraftSlots());
                }
            }
        };
    }

    @Override
    public boolean fastInsert() {
        return super.fastInsert();
    }

    @Override
    public int[] getIngredientSlots() {
        return getCraftSlots();
    }

    public int getJEGRecipeButtonSlot() {
        return 53;
    }

    @Override
    public int getJEGFindingButtonSlot() {
        return 49;
    }
}
