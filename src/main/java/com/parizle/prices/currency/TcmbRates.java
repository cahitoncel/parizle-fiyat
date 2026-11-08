package com.parizle.prices.currency;

import java.time.LocalDate;
import java.util.List;

public record TcmbRates(LocalDate rateDate, List<TcmbRate> rates) {
}
