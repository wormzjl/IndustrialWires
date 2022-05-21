/*
 * This file is part of Industrial Wires.
 * Copyright (C) 2016-2018 malte0811
 * Industrial Wires is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Industrial Wires is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Industrial Wires.  If not, see <http://www.gnu.org/licenses/>.
 */

package malte0811.industrialwires.blocks.converter;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IRedstoneOutput;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0;
import blusunrize.immersiveengineering.common.util.Utils;
import com.google.common.collect.MapMaker;
import ic2.api.energy.tile.IEnergyAcceptor;
import ic2.api.energy.tile.IEnergyEmitter;
import ic2.api.energy.tile.IEnergySink;
import ic2.api.energy.tile.IEnergySource;
import malte0811.industrialwires.IndustrialWires;
import malte0811.industrialwires.blocks.IBlockBoundsIW.IBlockBoundsDirectional;
import malte0811.industrialwires.blocks.ISyncReceiver;
import malte0811.industrialwires.blocks.TileEntityIWMultiblock;
import malte0811.industrialwires.compat.Compat;
import malte0811.industrialwires.mech_mb.*;
import malte0811.industrialwires.network.MessageTileSyncIW;
import malte0811.industrialwires.util.LocalSidedWorld;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

import static blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration0.HEAVY_ENGINEERING;
import static malte0811.industrialwires.IEObjects.blockMetalDecoration0;
import static malte0811.industrialwires.IndustrialWires.MMB_BREAKING;
import static malte0811.industrialwires.mech_mb.EUCapability.ENERGY_IC2;
import static malte0811.industrialwires.util.MiscUtils.getOffset;
import static malte0811.industrialwires.util.MiscUtils.offset;
import static malte0811.industrialwires.util.NBTKeys.*;

@net.minecraftforge.fml.common.Optional.InterfaceList({
		@net.minecraftforge.fml.common.Optional.Interface(iface = "ic2.api.energy.tile.IEnergySource", modid = "ic2"),
		@Optional.Interface(iface = "ic2.api.energy.tile.IEnergySink", modid = "ic2")
})
public class TileEntityMechMB extends TileEntityIWMultiblock implements ITickable, ISyncReceiver,
		IEnergySource, IEnergySink, IPlayerInteraction, IRedstoneOutput, IBlockBoundsDirectional {
	private static final double DECAY_BASE = Math.exp(Math.log(.95) / (60 * 60 * 20));
	public static final double TICK_ANGLE_PER_SPEED = 180 / 20 / Math.PI;
	private static final double SYNC_THRESHOLD = .95;
	private static final Map<BlockPos, TileEntityMechMB> CLIENT_MASTER_BY_POS = new MapMaker().weakValues().makeMap();
	public MechMBPart[] mechanical = null;
	int[] offsets = null;

	private int[][] electricalStartEnd = null;

	public MechEnergy energyState;
	private double lastSyncedSpeed = 0;
	private double decay;
	public double angle;
	@SideOnly(Side.CLIENT)
	public List<BakedQuad> rotatingModel;
	private boolean firstTick = true;
	// To allow changing the MB structure later on without resulting in dupes/conversion
	private int structureVersion = 0;

	@Override
	public void update() {
		ApiUtils.checkForNeedlessTicking(this);
		if (firstTick && !world.isRemote) {
			Compat.loadIC2Tile.accept(this);
			firstTick = false;
		}
		if (isLogicDummy() || mechanical == null || mechanical.length==0) {
			return;
		}
		if (world.isRemote) {
			angle += energyState.getSpeed() * TICK_ANGLE_PER_SPEED;
			angle %= 360;
			if (firstTick) {
				CLIENT_MASTER_BY_POS.put(pos, this);
			}
			if (energyState.clientUpdate()||firstTick) {
				IndustrialWires.proxy.updateMechMBTurningSound(this, energyState);
				TileEntity otherEnd = Utils.getExistingTileEntity(world, pos.offset(facing, -offsets[mechanical.length]));
				if (otherEnd instanceof TileEntityMechMB) {
					IndustrialWires.proxy.updateMechMBTurningSound((TileEntityMechMB) otherEnd, energyState);
				}
			}
			return;
		}
		// Mechanical
		for (MechMBPart part : mechanical) {
			part.createMEnergy(energyState);
		}
		double requestSum = 0;
		IdentityHashMap<MechMBPart, Double> individualRequests = new IdentityHashMap<>();
		for (MechMBPart part : mechanical) {
			double eForPart = part.requestMEnergy(energyState);
			requestSum += eForPart;
			individualRequests.put(part, eForPart);
		}
		double availableEnergy = energyState.getEnergy() / 5;//prevent energy transmission without movement
		double factor = Math.min(availableEnergy / requestSum, 1);
		energyState.extractEnergy(Math.min(requestSum, availableEnergy));
		for (MechMBPart part : mechanical) {
			part.insertMEnergy(factor * individualRequests.get(part));
		}
		Set<MechMBPart> failed = new HashSet<>();
		for (MechMBPart part : mechanical) {
			if (energyState.getSpeed() > part.getMaxSpeed()) {
				failed.add(part);
			}
		}
		if (!failed.isEmpty()) {
			disassemble(failed);
			return;
		}

		//Electrical
		for (int[] section : electricalStartEnd) {
			final int sectionLength = section[1] - section[0];
			double[] available = new double[sectionLength];
			Waveform[] availableWf = new Waveform[sectionLength];
			boolean hasEnergy = false;
			Set<Waveform> availableWaveforms = new HashSet<>();
			for (int i = section[0]; i < section[1]; i++) {
				IMBPartElectric electricalComp = ((IMBPartElectric) mechanical[i]);
				Waveform localWf = electricalComp.getProduced(energyState).getForSpeed(energyState.getSpeed());
				availableWf[i - section[0]] = localWf;
				if (!localWf.isEnergyWaveform()) {
					continue;
				}
				double availableLocal = electricalComp.getAvailableEEnergy(energyState);
				available[i - section[0]] = availableLocal;
				availableWaveforms.add(localWf);
				if (availableLocal > 0) {
					hasEnergy = true;
				}
			}
			if (hasEnergy) {
				List<Waveform> availableWfList = new ArrayList<>(availableWaveforms);
				double[][] requested = new double[availableWfList.size()][sectionLength];
				for (int i = 0; i < requested.length; i++) {
					Waveform wf = availableWfList.get(i);
					if (wf.isEnergyWaveform()) {
						for (int j = 0; j < sectionLength; j++) {
							requested[i][j] = ((IMBPartElectric) mechanical[j + section[0]]).requestEEnergy(wf, energyState);
						}
					}
				}
				int maxId = -1;
				double maxTransferred = 0;
				for (int i = 0; i < requested.length; i++) {
					Waveform wf = availableWfList.get(i);
					double transferred = transferElectric(section, Arrays.copyOf(available, sectionLength), availableWf, wf,
							Arrays.copyOf(requested[i], sectionLength), true);
					if (transferred > maxTransferred) {
						maxTransferred = transferred;
						maxId = i;
					}
				}
				if (maxId < 0) {
					double[] availablePerWf = new double[availableWaveforms.size()];
					for (int i = 0; i < availableWf.length; i++) {
						if (availableWf[i].isEnergyWaveform()) {
							availablePerWf[availableWfList.indexOf(availableWf[i])] += available[i];
						}
					}
					for (int i = 0; i < availablePerWf.length; i++) {
						if (availablePerWf[i] > 0 && (maxId < 0 || availablePerWf[maxId] < availablePerWf[i])) {
							maxId = i;
						}
					}
				}
				if (maxId >= 0) {
					transferElectric(section, available, availableWf, availableWfList.get(maxId), requested[maxId], false);
				}
			}
		}

		//General
		energyState.decaySpeed(decay);
		markDirty();
		if (lastSyncedSpeed < energyState.getSpeed() * SYNC_THRESHOLD || lastSyncedSpeed > energyState.getSpeed() / SYNC_THRESHOLD) {
			NBTTagCompound nbt = new NBTTagCompound();
			nbt.setDouble(SPEED, energyState.getSpeed());
			IndustrialWires.packetHandler.sendToDimension(new MessageTileSyncIW(this, nbt), world.provider.getDimension());
			lastSyncedSpeed = energyState.getSpeed();
		}
	}

	@Override
	public void setWorld(@Nonnull World worldIn) {
		super.setWorld(worldIn);
		if (!isLogicDummy()) {
			int offset = 1;
			for (MechMBPart part : mechanical) {
				part.world.setWorld(world);
				part.world.setOrigin(offset(pos, facing, mirrored, 0, -offset, 0));
				offset += part.getLength();
			}
		}
	}

	public IBlockState getExtState(IBlockState in) {
		TileEntityMechMB master = CLIENT_MASTER_BY_POS.get(pos.subtract(offset));
		if (master==null)
			return in;
		Vec3i offsetDirectional = getOffsetDir();
		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return in;
		}
		MechMBPart part = master.mechanical[id];
		return part.getExtState(in);
	}

	//return value is maximized to choose the waveform to use
	private double transferElectric(int[] section, double[] available, Waveform[] availableWf, Waveform waveform,
									double[] requested, boolean simulate) {
		double totalAvailable = 0;
		double totalRequested = 0;
		for (int i = 0; i < available.length; i++) {
			if (!availableWf[i].equals(waveform)) {
				available[i] = 0;
			}
			totalRequested += requested[i];
		}
		for (int i = 0; i < available.length; i++) {
			if (available[i]>0) {
				available[i] = Math.min(available[i], totalRequested-requested[i]);
				totalAvailable += available[i];
			}
		}
		double[] ins = new double[section[1]-section[0]];
		double[] extracted = new double[section[1]-section[0]];
		if (totalAvailable>0) {
			for (int i = section[0]; i < section[1]; i++) {
				int i0 = i - section[0];
				double otherRequests = totalRequested - requested[i0];
				double extractFactor = Math.min(1, otherRequests / totalAvailable);
				double extr = available[i0] * extractFactor;
				if (extr == 0) {
					continue;
				}
				for (int j = 0; j < section[1] - section[0]; j++) {
					if (j != i0) {
						ins[j] += extr * (requested[j] / otherRequests);
					}
				}
				extracted[i0] = extr;
				if (!simulate) {
					IMBPartElectric electric = (IMBPartElectric) mechanical[i];
					electric.extractEEnergy(extr, energyState);
				}
			}
		}
		if (!simulate) {
			for (int i = section[0]; i < section[1]; i++) {
				int i0 = i - section[0];
				IMBPartElectric electric = (IMBPartElectric) mechanical[i];
				electric.insertEEnergy(ins[i0], waveform, energyState);
			}
		}
		double totalTransf = 0;
		for (int i = 0; i < section[1] - section[0]; i++) {
			totalTransf += Math.abs(ins[i]-extracted[i]);
		}
		return totalTransf;
	}

	@Override
	public void writeNBT(NBTTagCompound out, boolean updatePacket) {
		super.writeNBT(out, updatePacket);
		if (mechanical != null) {
			NBTTagList mechParts = new NBTTagList();
			for (MechMBPart part : mechanical) {
				mechParts.appendTag(MechMBPart.toNBT(part));
			}
			out.setTag(PARTS, mechParts);
			out.setDouble(SPEED, energyState.getSpeed());
		}
		out.setInteger(VERSION, structureVersion);
	}

	@Override
	public void readNBT(NBTTagCompound in, boolean updatePacket) {
		super.readNBT(in, updatePacket);
		if (in.hasKey(PARTS, Constants.NBT.TAG_LIST)) {
			NBTTagList mechParts = in.getTagList(PARTS, Constants.NBT.TAG_COMPOUND);
			MechMBPart[] mech = new MechMBPart[mechParts.tagCount()];
			int offset = 1;
			for (int i = 0; i < mechParts.tagCount(); i++) {
				mech[i] = MechMBPart.fromNBT(mechParts.getCompoundTagAt(i),
						new LocalSidedWorld(world, offset(pos, facing, mirrored, 0, -offset, 0), facing.getOpposite(), mirrored));
				offset += mech[i].getLength();
			}
			setMechanical(mech, in.getDouble(SPEED));
		}
		structureVersion = in.getInteger(VERSION);
		rBB = null;
		aabb = null;
	}

	public void setMechanical(MechMBPart[] mech, double speed) {
		mechanical = mech;
		offsets = new int[mechanical.length+1];
		double weight = 0;
		int offset = 1;
		List<int[]> electrical = new ArrayList<>();
		int lastEStart = -1;
		for (int i = 0; i < mech.length; i++) {
			offsets[i] = offset;
			weight += mechanical[i].getInertia();
			offset += mechanical[i].getLength();
			if (lastEStart < 0 && mechanical[i] instanceof IMBPartElectric) {
				lastEStart = i;
			} else if (lastEStart >= 0 && !(mechanical[i] instanceof IMBPartElectric)) {
				electrical.add(new int[]{lastEStart, i});
				lastEStart = -1;
			}
		}
		offsets[mechanical.length] = offset;
		if (lastEStart >= 0) {
			electrical.add(new int[]{lastEStart, mechanical.length});
		}
		electricalStartEnd = electrical.toArray(new int[electrical.size()][]);
		decay = DECAY_BASE;
		if (energyState!=null) {
			energyState.invalid = true;
		}
		energyState = new MechEnergy(weight, speed);
	}

	private int getPart(int offset, TileEntityMechMB master) {
		if (offset == 0) {
			return -1;
		}
		int pos = 1;
		MechMBPart[] mechMaster = master.mechanical;
		if (mechMaster != null) {
			for (int i = 0, mechanical1Length = mechMaster.length; i < mechanical1Length; i++) {
				MechMBPart part = mechMaster[i];
				if (pos >= offset) {
					return i;
				}
				pos += part.getLength();
			}
		}
		return -1;
	}

	@Nonnull
	@Override
	protected BlockPos getOrigin() {
		return pos;//Irrelevant, since this uses a custom disassembly method
	}

	@Override
	public IBlockState getOriginalBlock() {
		return Blocks.AIR.getDefaultState();//Irrelevant, the method below is used for pick block
	}

	@Override
	public ItemStack getOriginalItem() {
		Vec3i offsetDirectional = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return new ItemStack(blockMetalDecoration0, 1,
					BlockTypes_MetalDecoration0.HEAVY_ENGINEERING.ordinal());
		}
		MechMBPart part = master.mechanical[id];
		BlockPos offsetPart = new BlockPos(offsetDirectional.getX(), offsetDirectional.getY(), offsetDirectional.getZ() - master.offsets[id]);
		return part.getOriginalItem(offsetPart);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public void onSync(NBTTagCompound nbt) {
		energyState.setTargetSpeed(nbt.getDouble(SPEED));
	}

	private AxisAlignedBB rBB;

	@Nonnull
	@Override
	public AxisAlignedBB getRenderBoundingBox() {
		if (rBB == null) {
			if (isLogicDummy()) {
				rBB = new AxisAlignedBB(pos, pos);
			} else {
				rBB = new AxisAlignedBB(offset(pos, facing, mirrored, -2, 0, -2),
						offset(pos, facing, mirrored, 2, -mechanical.length, 2));
			}
		}
		return rBB;
	}

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing) {
		Vec3i offsetDirectional = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return false;
		}
		MechMBPart part = master.mechanical[id];
		BlockPos offsetPart = new BlockPos(offsetDirectional.getX(), offsetDirectional.getY(), offsetDirectional.getZ() - master.offsets[id]);
		return part.hasCapability(capability, part.world.realToTransformed(facing), offsetPart);
	}

	@Nullable
	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing) {
		Vec3i offsetDirectional = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);

		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return null;
		}
		MechMBPart part = master.mechanical[id];
		BlockPos offsetPart = new BlockPos(offsetDirectional.getX(), offsetDirectional.getY(), offsetDirectional.getZ() - master.offsets[id]);
		return part.getCapability(capability, part.world.realToTransformed(facing), offsetPart);
	}

	@Override
	public void disassemble() {
		final double MIN_BREAK = .1;
		final double MIN_BREAK_BROKEN = .5;
		if (formed) {
			TileEntityMechMB master = master(this);
			if (master != null) {
				int partId = master.getPart(offset.getX(), master);
				MechMBPart broken = null;
				if (partId >= 0) {
					broken = master.mechanical[partId];
				}
				Set<MechMBPart> failed = new HashSet<>();
				for (MechMBPart part : master.mechanical) {
					if (master.energyState.getSpeed() > (part == broken ? MIN_BREAK_BROKEN : MIN_BREAK) * part.getMaxSpeed()) {
						failed.add(part);
					}
				}
				master.disassemble(failed);
				try {
					IBlockState state = world.getBlockState(pos);
					NonNullList<ItemStack> drops = NonNullList.create();
					state.getBlock().getDrops(drops, world, pos, state, 0);
					world.setBlockToAir(pos);
					for (ItemStack s:drops) {
						Block.spawnAsEntity(world, pos, s);
					}
				} catch (Exception x) {
					x.printStackTrace();
				}
			}
		}
	}

	private void disassemble(Set<MechMBPart> failed) {
		if (!world.isRemote && formed) {
			formed = false;
			world.setBlockState(pos,
					blockMetalDecoration0.getDefaultState().withProperty(blockMetalDecoration0.property, HEAVY_ENGINEERING));
			world.setBlockState(pos.down(),
					blockMetalDecoration0.getDefaultState().withProperty(blockMetalDecoration0.property, HEAVY_ENGINEERING));
			for (MechMBPart mech : mechanical) {
				if (failed.contains(mech)) {
					world.playSound(null, mech.world.getOrigin(), MMB_BREAKING, SoundCategory.BLOCKS, 1, 1);

					mech.breakOnFailure(energyState);
				} else {
					mech.disassemble();
				}
				for (int l = 0;l<mech.getLength();l++) {
					short pattern = mech.getFormPattern(l);
					for (int i = 0; i < 9; i++) {
						if (((pattern >> i) & 1) != 0) {
							BlockPos pos = new BlockPos(i % 3 - 1, i / 3 - 1, -l);
							if (mech.world.getBlockState(pos).getBlock() == IndustrialWires.mechanicalMB) {
								mech.world.setBlockState(pos, Blocks.AIR.getDefaultState());
							}
						}
					}
				}
			}
			BlockPos otherEnd = offset(pos, facing.getOpposite(), mirrored, 0,
					offsets[mechanical.length], 0);
			world.setBlockState(otherEnd,
					blockMetalDecoration0.getDefaultState().withProperty(blockMetalDecoration0.property, HEAVY_ENGINEERING));
			world.setBlockState(otherEnd.down(),
					blockMetalDecoration0.getDefaultState().withProperty(blockMetalDecoration0.property, HEAVY_ENGINEERING));
		}
	}

	private EUCapability.IC2EnergyHandler getIC2Cap() {
		return ENERGY_IC2 != null ? getCapability(ENERGY_IC2, null) : null;
	}

	@Override
	public boolean emitsEnergyTo(IEnergyAcceptor output, EnumFacing side) {
		if (ENERGY_IC2 == null)
			return false;
		Vec3i offsetDirectional = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return false;
		}
		MechMBPart part = master.mechanical[id];
		BlockPos offsetPart = new BlockPos(offsetDirectional.getX(), offsetDirectional.getY(), offsetDirectional.getZ() - master.offsets[id]);
		EUCapability.IC2EnergyHandler cap = part.getCapability(ENERGY_IC2, part.world.realToTransformed(side), offsetPart);
		return cap != null;
	}

	@Override
	public double getDemandedEnergy() {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		return cap != null ? cap.getDemandedEnergy() : 0;
	}

	@Override
	public int getSinkTier() {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		return cap != null ? cap.getEnergyTier() : 0;
	}

	@Override
	public double injectEnergy(EnumFacing enumFacing, double amount, double voltage) {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		return cap != null ? cap.injectEnergy(enumFacing, amount, voltage) : 0;
	}

	@Override
	public boolean acceptsEnergyFrom(IEnergyEmitter input, EnumFacing side) {
		if (ENERGY_IC2 == null)
			return false;
		Vec3i offsetDirectional = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(offsetDirectional.getZ(), master);
		if (id < 0) {
			return false;
		}
		MechMBPart part = master.mechanical[id];
		BlockPos offsetPart = new BlockPos(offsetDirectional.getX(), offsetDirectional.getY(), offsetDirectional.getZ() - master.offsets[id]);
		EUCapability.IC2EnergyHandler cap = part.getCapability(ENERGY_IC2, part.world.realToTransformed(side), offsetPart);
		return cap != null;
	}

	@Override
	public double getOfferedEnergy() {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		return cap != null ? cap.getOfferedEnergy() : 0;
	}

	@Override
	public void drawEnergy(double amount) {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		if (cap != null) {
			cap.drawEnergy(amount);
		}
	}

	@Override
	public int getSourceTier() {
		EUCapability.IC2EnergyHandler cap = getIC2Cap();
		return cap != null ? cap.getEnergyTier() : 0;
	}


	@Override
	public void invalidate() {
		if (!world.isRemote && !firstTick)
			Compat.unloadIC2Tile.accept(this);
		else if (world.isRemote)
			CLIENT_MASTER_BY_POS.remove(pos);
		firstTick = true;
		if (energyState!=null)
			energyState.invalid = true;
		super.invalidate();
	}

	@Override
	public void onChunkUnload() {
		super.onChunkUnload();
		if (!world.isRemote && !firstTick)
			Compat.unloadIC2Tile.accept(this);
		else if (world.isRemote)
			CLIENT_MASTER_BY_POS.remove(pos);
		if (energyState!=null)
			energyState.invalid = true;
 		firstTick = true;
	}

	@Override
	public boolean interact(@Nonnull EnumFacing side, @Nonnull EntityPlayer player, @Nonnull EnumHand hand,
							@Nonnull ItemStack heldItem, float hitX, float hitY, float hitZ) {
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(getOffsetDir().getZ(), master);
		if (id >= 0) {
			MechMBPart part = master.mechanical[id];
			side = part.world.realToTransformed(side);
			int ret = part.interact(side, getOffsetDir().add(0, 0, - master.offsets[id]),
					player, hand, heldItem);
			if (ret>=0) {
				if ((ret&1)!=0) {
					IBlockState state = world.getBlockState(master.pos);
					world.notifyBlockUpdate(master.pos, state, state, 3);
					world.addBlockEvent(master.pos, state.getBlock(), 255, id);
				}
				return true;
			}
		}
		return false;
	}

	private BlockPos getOffsetDir() {
		BlockPos offset = getOffset(BlockPos.NULL_VECTOR, facing, mirrored, this.offset);
		return new BlockPos(offset.getX(), offset.getZ(), offset.getY());
	}

	@Override
	public int getStrongRSOutput(@Nonnull IBlockState state, @Nonnull EnumFacing side) {
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(getOffsetDir().getZ(), master);
		if (id >= 0 && master.mechanical[id] instanceof IRedstoneOutput) {
			MechMBPart part = master.mechanical[id];
			return ((IRedstoneOutput) part).getStrongRSOutput(state,
					part.world.realToTransformed(side));
		}
		return 0;
	}

	@Override
	public boolean canConnectRedstone(@Nonnull IBlockState state, @Nonnull EnumFacing side) {
		TileEntityMechMB master = masterOr(this, this);
		int id = getPart(getOffsetDir().getZ(), master);
		if (id >= 0 && master.mechanical[id] instanceof IRedstoneOutput) {
			MechMBPart part = master.mechanical[id];
			return ((IRedstoneOutput) part).canConnectRedstone(state,
					part.world.realToTransformed(side));
		}
		return false;
	}

	@Override
	public AxisAlignedBB getBoundingBoxNoRot() {
		Vec3i offset = getOffsetDir();
		TileEntityMechMB master = masterOr(this, this);
		if (master==this&&!offset.equals(Vec3i.NULL_VECTOR))
			return new AxisAlignedBB(0, 0, 0, 0, 0, 0);
		int comp = getPart(offset.getZ(), master);
		if (comp < 0) {
			if (offset.getZ() == 0) {
				return new AxisAlignedBB(0, 0, .25, 1, 1, 1 + offset.getY() * .25);
			} else {
				return new AxisAlignedBB(0, 0, -offset.getY() * .25, 1, 1, .75);
			}
		}
		MechMBPart part = master.mechanical[comp];
		BlockPos offsetPart = new BlockPos(offset.getX(), offset.getY(), offset.getZ() - master.offsets[comp]);
		return part.getBoundingBox(offsetPart);
	}

	public AxisAlignedBB aabb = null;

	@Override
	public AxisAlignedBB getBoundingBox() {
		if (aabb == null || aabb.minX==aabb.maxX)
		{
			aabb = IBlockBoundsDirectional.super.getBoundingBox();
		}
		return aabb;
	}
}
