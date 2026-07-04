
module.exports = function(runtime, global){
    var floaty = {};

    floaty.window = function(xml){
        if(typeof(xml) == 'xml'){
            xml = xml.toXMLString();
        }
        return wrap(runtime.floaty.window(function(context, parent){
             runtime.ui.layoutInflater.setContext(context);
             return runtime.ui.layoutInflater.inflate(xml.toString(), parent, true);
        }));
    }

    floaty.rawWindow = function(xml){
        if(typeof(xml) == 'xml'){
            xml = xml.toXMLString();
        }
        return wrap(runtime.floaty.rawWindow(function(context, parent){
             runtime.ui.layoutInflater.setContext(context);
             return runtime.ui.layoutInflater.inflate(xml.toString(), parent, true);
        }));
    }

    function wrap(window){
        var proxyObject = new com.stardust.autojs.rhino.ProxyJavaObject(global, window, window.getClass());
        var viewCache = {};
        proxyObject.__proxy__ = {
            set: function(name, value){
                window[name] = value;
            },
            get: function(name) {
               var value = window[name];
               if(typeof(value) == 'undefined'){
                   if(!value){
                        value = window.findView(name);
                   }
                   if(!value){
                      value = undefined;
                   }
               }
               return value;
            }
        };
        return proxyObject;
    }
    
    floaty.closeAll = runtime.floaty.closeAll.bind(runtime.floaty);

    floaty.checkPermission = runtime.floaty.checkPermission.bind(runtime.floaty);

    floaty.requestPermission = runtime.floaty.requestPermission.bind(runtime.floaty);

    // ===== 系统自带悬浮菜单（悬浮小球 CircularMenu）控制 =====
    // 场景：脚本运行时靠截图识别/点击，自带悬浮小球会遮挡屏幕、干扰截图识别或误触，
    //      故开放接口，允许脚本运行时先隐藏它、结束后再还原。
    // 实现说明：小球由 app 模块的 FloatyWindowManger 管理，autojs 模块无法在编译期反向依赖 app，
    //          但 Rhino 运行时是单一 classloader，可按全限定类名在运行时解析该类。
    var _fwmResolved = false, _fwm = null;
    function floatyWindowManger(){
        if (_fwmResolved) return _fwm;
        _fwmResolved = true;
        try {
            var clazz = Packages.org.autojs.autojs.ui.floating.FloatyWindowManger;
            clazz.isCircularMenuShowing(); //探测调用：类缺失（如精简打包）时会抛错
            _fwm = clazz;
        } catch (e) {
            _fwm = null; //当前构建不含该类，接口降级为无操作
        }
        return _fwm;
    }

    // 添加/移除悬浮窗必须在主线程执行；隐藏后脚本通常紧接着截图，故这里同步等待执行完成。
    function runOnUiSync(fn){
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            fn();
            return;
        }
        var dispose = new com.stardust.concurrent.VolatileDispose();
        runtime.uiHandler.post(function(){
            try { fn(); } finally { dispose.setAndNotify(true); }
        });
        dispose.blockedGet();
    }

    // 查询自带悬浮小球当前是否显示
    floaty.isCircularMenuShowing = function(){
        var mgr = floatyWindowManger();
        if (!mgr) return false;
        return mgr.isCircularMenuShowing();
    }

    // 隐藏自带悬浮小球，返回隐藏前是否处于显示状态（供脚本记录、结束后还原）
    // 注意：仅切换可见性、不销毁实例，从而保留贴边位置与半透明状态（销毁+重建会使其复位到居中且不透明）
    floaty.hideCircularMenu = function(){
        var mgr = floatyWindowManger();
        if (!mgr) return false;
        var showing = mgr.isCircularMenuShowing();
        if (showing) {
            runOnUiSync(function(){ mgr.setCircularMenuVisible(false); });
        }
        return showing;
    }

    // 显示（还原）自带悬浮小球，返回接口是否可用
    floaty.showCircularMenu = function(){
        var mgr = floatyWindowManger();
        if (!mgr) return false;
        runOnUiSync(function(){ mgr.setCircularMenuVisible(true); });
        return true;
    }

    return floaty;
}

