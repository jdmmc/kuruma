# 1 本の直線を路面ごとに区切る。走りながら切り替わりを見るためのもの。
# 立った場所から +X へ 300 ブロック、幅 12（Z-6..Z+5）。
#
#   0..39    石     200..239 氷
#   40..79   草     240..300 砂利（長い走り込み用）
#   80..119  砂利
#   120..159 砂
#   160..199 雪
function kurumatest:setup

fill ~ ~ ~-6 ~300 ~4 ~5 air
fill ~ ~-1 ~-6 ~300 ~-1 ~5 stone
fill ~40 ~-1 ~-6 ~79 ~-1 ~5 grass_block
fill ~80 ~-1 ~-6 ~119 ~-1 ~5 gravel
fill ~120 ~-1 ~-6 ~159 ~-1 ~5 sand
fill ~160 ~-1 ~-6 ~199 ~-1 ~5 snow_block
fill ~200 ~-1 ~-6 ~239 ~-1 ~5 packed_ice
fill ~240 ~-1 ~-6 ~300 ~-1 ~5 gravel

fill ~ ~-1 ~-6 ~ ~-1 ~5 white_concrete

tellraw @s {"text":"[kuruma] 区間コースを作りました。東（+X）へ 300m。","color":"green"}
