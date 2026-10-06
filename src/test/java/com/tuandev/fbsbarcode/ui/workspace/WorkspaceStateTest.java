package com.tuandev.fbsbarcode.ui.workspace;

import com.tuandev.fbsbarcode.models.Shop;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceStateTest {
    @Test
    void deletingSelectedShopChoosesTheNextAvailableShop() {
        WorkspaceState state = stateWithSelected(2);

        Shop fallback = state.removeShopAndChooseFallback(2);

        assertEquals(3, fallback.getId());
        assertEquals(List.of(1, 3), state.getShops().stream().map(Shop::getId).toList());
    }

    @Test
    void deletingLastSelectedShopChoosesThePreviousShop() {
        WorkspaceState state = stateWithSelected(3);

        Shop fallback = state.removeShopAndChooseFallback(3);

        assertEquals(2, fallback.getId());
    }

    @Test
    void deletingOnlyShopLeavesWorkspaceWithoutSelection() {
        WorkspaceState state = new WorkspaceState();
        Shop only = shop(1);
        state.setShops(List.of(only));
        state.setSelectedShop(only);

        assertNull(state.removeShopAndChooseFallback(1));
        assertNull(state.getSelectedShop());
    }

    private static WorkspaceState stateWithSelected(int selectedId) {
        WorkspaceState state = new WorkspaceState();
        List<Shop> shops = List.of(shop(1), shop(2), shop(3));
        state.setShops(shops);
        state.setSelectedShop(shops.stream()
                .filter(shop -> shop.getId() == selectedId).findFirst().orElseThrow());
        return state;
    }

    private static Shop shop(int id) {
        return new Shop(id, "Shop " + id, "token-" + id);
    }
}
