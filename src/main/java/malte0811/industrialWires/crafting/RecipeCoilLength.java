/*
 * This file is part of Industrial Wires.
 * Copyright (C) 2016-2017 malte0811
 *
 * Industrial Wires is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Industrial Wires is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Industrial Wires.  If not, see <http://www.gnu.org/licenses/>.
 */
package malte0811.industrialWires.crafting;

import malte0811.industrialWires.IndustrialWires;
import malte0811.industrialWires.items.ItemIC2Coil;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.registries.IForgeRegistryEntry;

import javax.annotation.Nonnull;

public class RecipeCoilLength extends IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
	public final ItemStack coil;
	public final ItemStack cable;
	private final int maxLength;

	public RecipeCoilLength(int meta) {
		coil = new ItemStack(IndustrialWires.coil, 1, meta);
		cable = ItemIC2Coil.getUninsulatedCable(coil);
		maxLength = ItemIC2Coil.getMaxWireLength(coil);
	}

	@Override
	public boolean matches(@Nonnull InventoryCrafting inv, @Nonnull World worldIn) {
		int l = getLength(inv);
		return l > 0;
	}

	@Nonnull
	@Override
	public ItemStack getCraftingResult(@Nonnull InventoryCrafting inv) {
		ItemStack ret = new ItemStack(IndustrialWires.coil, 1, coil.getItemDamage());
		ItemIC2Coil.setLength(ret, Math.min(maxLength, getLength(inv)));
		return ret;
	}

	@Override
	public boolean canFit(int width, int height) {
		return width>0 && height>0;
	}

	@Nonnull
	@Override
	public ItemStack getRecipeOutput() {
		return ItemStack.EMPTY;
	}

	@Nonnull
	@Override
	public NonNullList<ItemStack> getRemainingItems(@Nonnull InventoryCrafting inv) {
		NonNullList<ItemStack> ret = NonNullList.withSize(inv.getSizeInventory(), ItemStack.EMPTY);
		int length = Math.min(getLength(inv), maxLength);
		for (int i = 0; i < ret.size() && length > 0; i++) {
			ItemStack curr = inv.getStackInSlot(i);
			if (OreDictionary.itemMatches(curr, coil, false)) {
				length -= ItemIC2Coil.getLength(curr);
				if (length < 0) {
					ItemStack currStack = new ItemStack(IndustrialWires.coil, 1);
					ret.set(i, currStack);
					ItemIC2Coil.setLength(currStack, -length);
				}
			} else if (OreDictionary.itemMatches(curr, cable, false)) {
				length--;
			}
		}
		return ret;
	}

	private int getLength(InventoryCrafting inv) {
		int cableLength = 0;
		for (int i = 0; i < inv.getSizeInventory(); i++) {
			ItemStack curr = inv.getStackInSlot(i);
			if (OreDictionary.itemMatches(curr, coil, false)) {
				cableLength += ItemIC2Coil.getLength(curr);
			} else if (OreDictionary.itemMatches(curr, cable, false)) {
				cableLength++;
			} else if (!curr.isEmpty()) {
				return -1;
			}
		}
		return cableLength;
	}
}
