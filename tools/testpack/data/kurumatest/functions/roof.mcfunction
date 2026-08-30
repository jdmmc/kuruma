# 屋根を掛ける。雨のときに「トンネルの下では土煙が舞い続ける」ことの確認用。
# 立った場所から +X 60..120 の上に蓋をする（strip の砂利区間のあたり）。
fill ~60 ~5 ~-7 ~120 ~5 ~6 stone

tellraw @s {"text":"[kuruma] 屋根を掛けました。/function kurumatest:wet で雨にして走ると、屋根の下だけ土煙が出ます。","color":"green"}
