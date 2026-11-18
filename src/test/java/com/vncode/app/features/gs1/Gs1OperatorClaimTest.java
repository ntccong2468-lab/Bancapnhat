package com.vncode.app.features.gs1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Gs1OperatorClaimTest {
    @TempDir Path root;
    private static final String INN="7707083893", KIND="OPERATOR_AGREEMENT:"+"a".repeat(64);
    private Gs1Repository.Request draft(Gs1Repository repo,String inn,String kind){return repo.createRequest(inn,kind,"True API","Reviewed operator PDF","Exact reviewed document digest");}
    @Test void acknowledgedOperatorSignatureBlocksOtherReviewedDraftsBeforeReadbackCompletes(){
        Path file=root.resolve("claim.db");var firstInstance=new Gs1Repository(file);var secondInstance=new Gs1Repository(file);
        var first=draft(firstInstance,INN,KIND);var second=draft(secondInstance,INN,KIND);
        assertTrue(firstInstance.claimSend(INN,first.id()));firstInstance.setState(INN,first.id(),Gs1Repository.State.SENT);
        assertTrue(secondInstance.hasBlockingOperation(INN,KIND));
        assertFalse(secondInstance.claimSend(INN,second.id()),"A successful signature acknowledgement awaiting readback must prevent a second POST from another instance");
        assertEquals(Gs1Repository.State.DRAFT,secondInstance.request(INN,second.id()).state());
        var unrelated=draft(secondInstance,INN,"OPERATOR_AGREEMENT:"+"b".repeat(64));assertTrue(secondInstance.claimSend(INN,unrelated.id()));
        var otherEnterprise=draft(secondInstance,"7811089030",KIND);assertTrue(secondInstance.claimSend(otherEnterprise.inn(),otherEnterprise.id()));
    }
    @Test void pendingAndUncertainOperatorSignaturesRemainBlockedButConfirmedRejectionAllowsNewReview(){
        for(var state:List.of(Gs1Repository.State.SENDING,Gs1Repository.State.RECONCILE_REQUIRED,Gs1Repository.State.REJECTED)){
            var repo=new Gs1Repository(root.resolve(state.name()+".db"));var original=draft(repo,INN,KIND);repo.setState(INN,original.id(),state);var another=draft(repo,INN,KIND);
            boolean rejected=state==Gs1Repository.State.REJECTED;
            assertEquals(!rejected,repo.hasBlockingOperation(INN,KIND));assertEquals(rejected,repo.claimSend(INN,another.id()));
        }
    }
}
