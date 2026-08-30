# 路面が勝手に変わらないようにする。
# snow_block も ice も明るいところではランダムティックで溶けるので、
# 止めておかないとテスト中に雪と氷のレーンが消える
gamerule randomTickSpeed 0
gamerule doWeatherCycle false
weather clear
time set noon
