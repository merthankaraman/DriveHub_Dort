package com.drivehub.kadran;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/**
 * Sayfa 0: kadran (sol), sayfa 1: pist modu (sağ). Başlangıç kadran.
 */
public class MainPagerAdapter extends FragmentStateAdapter {

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new DashboardFragment();
        }
        return new TrackFragment();
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
