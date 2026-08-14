package com.jdmmc.kurumamod.item;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 地面を右クリックすると車を出すアイテム。
 *
 * <p><b>アイテムは 1 種類のまま、どの車を出すかは NBT に持つ。</b>車種はデータパックから
 * 来るので、車種ごとに {@code Item} を登録することはできない——<b>データパックが読まれる
 * 時点でレジストリは既に凍結されている</b>ため。クリエイティブタブには
 * {@link #stackFor(ResourceLocation)} で作った「車種ぶんの ItemStack」を並べるので、
 * 遊ぶ側からは車種ごとにアイテムがあるように見える。</p>
 */
public class CarSpawnItem extends Item {

    /** NBT のキー。車種の id を文字列で入れる。 */
    private static final String CAR_ID = "CarId";

    public CarSpawnItem(Properties properties) {
        super(properties);
    }

    /** その車種を出すアイテム。クリエイティブタブと {@code getPickResult} が使う。 */
    public static ItemStack stackFor(ResourceLocation carId) {
        ItemStack stack = new ItemStack(Kurumamod.CAR_ITEM.get());
        if (!carId.equals(CarTypes.DEFAULT_ID)) {
            // 既定の車には書かない。NBT の付いていないアイテムと同じ物として重なる
            stack.getOrCreateTag().putString(CAR_ID, carId.toString());
        }
        return stack;
    }

    /** そのアイテムが出す車種。壊れた NBT や知らない id なら既定の車。 */
    public static CarType carTypeOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(CAR_ID)) {
            return CarTypes.get(CarTypes.DEFAULT_ID);
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(CAR_ID));
        return id != null ? CarTypes.get(id) : CarTypes.get(CarTypes.DEFAULT_ID);
    }

    /**
     * アイテム名は車種の名前。
     *
     * <p>カーパックが lang を用意していなければ id のパスがそのまま出る
     * （{@link CarType#displayName()}）。「item.kurumamod.car」と出るよりはましなため。</p>
     */
    @Override
    public Component getName(ItemStack stack) {
        return carTypeOf(stack).displayName();
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        // クリックした面の隣、つまりブロックの上側に出す
        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        CarEntity car = Kurumamod.CAR.get().create(level);
        if (car == null) {
            return InteractionResult.FAIL;
        }

        // 車高は諸元で決まるので、置く前に車種を入れておくこと。
        // 後から入れると、既定の車高で置かれてから諸元が変わり、地面へめり込む／浮く
        car.setCarType(carTypeOf(context.getItemInHand()));

        Player player = context.getPlayer();
        float yRot = (player != null) ? player.getYRot() : 0.0F;
        // エンティティの Y はシャシー基準面を指すので、地面から車高ぶん持ち上げて置く。
        // 地面に置くとサスペンションが縮みきった状態から始まり、飛び上がってしまう
        car.moveTo(pos.getX() + 0.5, pos.getY() + car.getStaticRideHeight(), pos.getZ() + 0.5, yRot, 0.0F);
        level.addFreshEntity(car);

        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
