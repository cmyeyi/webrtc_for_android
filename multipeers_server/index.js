'use strict';

var os = require('os');
var nodeStatic = require('node-static');
var https = require('https');
var socketIO = require('socket.io');
var fs = require('fs');
var options = {
    key: fs.readFileSync('./key.pem'),
    cert: fs.readFileSync('./cert.pem')
};

var fileServer = new (nodeStatic.Server)();
var app = https.createServer(options, function (req, res) {
    fileServer.serve(req, res);
}).listen(8888);

var io = socketIO.listen(app);
io.sockets.on('connection', function (socket) {

    // convenience function to log server messages on the client
    function log() {
        var array = ['Message from server:'];
        array.push.apply(array, arguments);//数组追加元素
        socket.emit('log', array);//emit发送事件，客户端对应的on获取log事件
        //
        console.log(array);
    }

    //消息转发，获取message中的to对应的socket.id,将来自于from的消息转发给to
    socket.on('message', function (message) {
        // for a real app, would be room-only (not broadcast)
        // socket.broadcast.emit('message', message);

        var to = message['to'];
        log('from:' + socket.id + " to:" + to, message);
        io.sockets.sockets[to].emit('message', message);//发送消息给指定socketId的客户端,这里是把收到的消息转发给to
    });

    socket.on('create or join', function (room) {
        log('Received request to create or join room ' + room);

        var clientsInRoom = io.sockets.adapter.rooms[room];
        var numClients = clientsInRoom ? Object.keys(clientsInRoom.sockets).length : 0;
        log('Room ' + room + ' now has ' + numClients + ' client(s)');

        if (numClients === 0) {
            //第一个进入房间者，一般是房间的创建者，加入房间时房间里面没有人
            socket.join(room);//调用join加入房间
            log('Client ID ' + socket.id + ' created room ' + room);
            socket.emit('created', room, socket.id);//告诉客户端，房间被创建了，客户端对应的socket.on("created")args -> {}会收到消息

        } else {
            //从第二个加入这开始，走这个分支
            log('Client ID ' + socket.id + ' joined room ' + room);
            io.sockets.in(room).emit('join', room, socket.id);//in()方法用于在指定的房间中，通过join事件将新加入者的socket.id发送给其他人
            socket.join(room);
            socket.emit('joined', room, socket.id);//发送joined事件给socket.id指定的客户端，
            io.sockets.in(room).emit('ready');//向房间中所有的人发送ready事件消息
        }
    });

    socket.on('ipaddr', function () {
        var ifaces = os.networkInterfaces();
        for (var dev in ifaces) {
            ifaces[dev].forEach(function (details) {
                if (details.family === 'IPv4' && details.address !== '192.168.101.5') {
                    socket.emit('ipaddr', details.address);
                }
            });
        }
    });

    socket.on('bye', function (room) {
        console.log('received bye,room ' + room);
        io.sockets.in(room).emit('bye', room, socket.id);//in()方法用于在指定的房间中，通过bye事件将新加入者的socket.id发送给其他人
    });

});
