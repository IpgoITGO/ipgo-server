package com.ipgo.server.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only in scripts/testing/verify-monthly-insight-budget.sh's isolated, disposable PostgreSQL. */
public final class MonthlyInsightBudgetPostgresProbe {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("IPGO_BUDGET_PROBE_URL");
        if(url==null || !url.endsWith("/ipgo_budget_test"))throw new IllegalArgumentException("Disposable budget DB required");
        var ds=new DriverManagerDataSource(url,"ipgo_server","fixture-only");
        var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var first=new MonthlyInsightBudget(jdbc,tx,BigDecimal.TEN);
        var second=new MonthlyInsightBudget(jdbc,tx,BigDecimal.TEN);
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<java.util.concurrent.Callable<MonthlyInsightBudget.Decision>>();
            for(int n=0;n<10;n++){int id=n;tasks.add(()->(id%2==0?first:second).tryDispatch("attempt-"+id));}
            int granted=0;for(var task:pool.invokeAll(tasks))if(task.get()==MonthlyInsightBudget.Decision.GRANTED)granted++;
            check(granted==2,"two independent instances exceeded remaining allowance");
        }
        check(jdbc.queryForObject("SELECT reserved_calls FROM insight_monthly_usage",Integer.class)==1000,"reservation count");
        jdbc.update("DELETE FROM ai_explanation_calls");
        jdbc.update("INSERT INTO ai_explanation_calls(attempt_id,status) VALUES ('after-delete','PROCESSING')");
        check(new MonthlyInsightBudget(jdbc,tx,BigDecimal.TEN).tryDispatch("after-delete")==MonthlyInsightBudget.Decision.EXHAUSTED,"deletion/restart refunded allowance");
        boolean denied=false;
        try{jdbc.update("DELETE FROM insight_monthly_usage");}catch(org.springframework.dao.DataAccessException e){denied=true;}
        check(denied,"runtime can delete usage counter");denied=false;
        try{jdbc.update("UPDATE insight_monthly_usage SET reserved_calls=0");}catch(org.springframework.dao.DataAccessException e){denied=true;}
        check(denied,"runtime can refund allowance");
        System.out.println("PostgreSQL monthly budget: concurrent multi-instance admission 2/10; hard ceiling1000; deletion/restart no refill; runtime delete/reset denied");
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
