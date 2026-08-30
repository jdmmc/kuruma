tellraw @s {"text":"--- KurumaMod 土煙テスト ---","color":"gold","bold":true}
tellraw @s {"text":"西を向いて（+X が正面になるように）立ってから撃つこと。","color":"gray"}
tellraw @s [{"text":"/function kurumatest:lanes","color":"yellow"},{"text":"  7 本の並走レーン（石・草・砂利・砂・赤砂・雪・氷）","color":"white"}]
tellraw @s [{"text":"/function kurumatest:strip","color":"yellow"},{"text":"  路面が切り替わる 300m の直線","color":"white"}]
tellraw @s [{"text":"/function kurumatest:split","color":"yellow"},{"text":"  左が砂利・右が石。片輪だけダートに落とす","color":"white"}]
tellraw @s [{"text":"/function kurumatest:roof","color":"yellow"},{"text":"  屋根を掛ける（雨でも舞うことの確認）","color":"white"}]
tellraw @s [{"text":"/function kurumatest:wet","color":"yellow"},{"text":"  雨にする（土煙が止まる）","color":"white"}]
tellraw @s [{"text":"/function kurumatest:dry","color":"yellow"},{"text":"  晴れの昼に戻す","color":"white"}]
tellraw @s [{"text":"/function kurumatest:clear","color":"yellow"},{"text":"  石に均す","color":"white"}]
tellraw @s {"text":"車は /give @s kurumamod:car。量の調整は H キーの「土煙の量」。","color":"gray"}
