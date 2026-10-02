package com.jdmmc.kurumamod.item;

import com.jdmmc.kurumamod.Kurumamod;
import com.jdmmc.kurumamod.car.CarType;
import com.jdmmc.kurumamod.car.CarTypes;
import com.jdmmc.kurumamod.entity.CarEntity;
import com.jdmmc.kurumamod.part.PartFitment;
import com.jdmmc.kurumamod.tuning.CarSpecCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
    /**
     * 諸元と装着した部品。<b>キーは {@code CarEntity} の NBT と同じ名前にしてある。</b>
     *
     * <p>同じものを指すキーが 2 つあると、片方を直したときにもう片方が取り残される。</p>
     */
    private static final String SPEC = "Spec";
    private static final String PARTS = "Parts";

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

    /**
     * その車を<b>設定ごと</b>アイテムにする。シフト＋殴りで回収したときに作られる。
     *
     * <p>車種だけでなく諸元と装着した部品も入るので、置き直せば同じ車が出てくる。
     * <b>NBT が違うので他のアイテムと重ならない</b>——セッティングの違う車が
     * 1 個のスタックに混ざらないという意味で、これは正しい振る舞い。</p>
     */
    public static ItemStack stackFor(CarEntity car) {
        ItemStack stack = stackFor(car.getCarId());
        stack.getOrCreateTag().put(SPEC, CarSpecCodec.save(car.getSpec()));
        PartFitment parts = car.getFitment();
        if (!parts.isEmpty()) {
            stack.getOrCreateTag().put(PARTS, parts.save());
        }
        return stack;
    }

    /**
     * アイテムに入っている設定を車へ移す。<b>置く前に呼ぶこと。</b>
     *
     * <p>車高は諸元で決まるので、置いてから入れると<b>既定の車高で置かれてから諸元が変わり、
     * 地面へめり込む／浮く</b>。</p>
     */
    public static void applyTo(CarEntity car, ItemStack stack) {
        // 車種を先に入れる。諸元はこの上から被せる（保存された値のほうが新しい）
        car.setCarType(carTypeOf(stack));
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        if (tag.contains(SPEC)) {
            car.setSpec(CarSpecCodec.load(tag.getCompound(SPEC)));
        }
        if (tag.contains(PARTS)) {
            car.setFitment(PartFitment.load(tag.getCompound(PARTS)));
        }
    }

    /** そのアイテムが設定を持っているか。持ち物の画面で見分けるために使う。 */
    private static boolean hasSettings(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && (tag.contains(SPEC) || tag.contains(PARTS));
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

    /**
     * 設定を持っているアイテムはそう出す。
     *
     * <p>持ち物の中では見た目が同じなので、<b>作り込んだ車と出したての車が見分けられない</b>。
     * 名前は車種のままにして、行を 1 本足すだけにしてある。</p>
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines,
                                TooltipFlag flag) {
        if (hasSettings(stack)) {
            lines.add(Component.translatable("item.kurumamod.car.saved")
                    .withStyle(ChatFormatting.GRAY));
        }
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

        // 車種・諸元・装着した部品をまとめて移す。置く前に済ませること（車高が変わるため）
        applyTo(car, context.getItemInHand());

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
