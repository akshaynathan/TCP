edge 0 1 lossRate 0.5 delay 200 bw 10000 bt 1000
time + 5
# server port backlog [servint workint sz]
0 server 21 2
time + 5
# transfer dest port localPort amount [interval sz]
1 transfer 0 21 40 50000
1 transfer 0 21 41 500
1 transfer 0 21 42 220
time + 1000000
time + 10
exit
