import java.util.Timer;
import java.util.TimerTask;
import java.lang.reflect.Method;


public class JavaTimer {
    public Timer timer;

    public JavaTimer(long ms, Object obj, String methodName, String[] paramTypes, Object[] params){
        try{
            timer = new Timer();
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    try{
                        Method method = Callback.getMethod(methodName, obj, paramTypes);
                        Callback cb = new Callback(method, obj, params);
                        cb.invoke();
                    }
                    catch (Exception e){
                        e.printStackTrace();
                    }
                    timer.cancel();
                }
            };
            timer.schedule(task, ms, 1);
        }
        catch (Exception e){
            e.printStackTrace();
        }
    }
}


